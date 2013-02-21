package com.wikia.phalanx

import com.twitter.concurrent.NamedPoolThreadFactory
import com.twitter.finagle.builder.ServerBuilder
import com.twitter.finagle.http.RichHttp
import com.twitter.finagle.http.filter.ExceptionFilter
import com.twitter.finagle.http.{Http, Request, Status, Version, Response, Message}
import com.twitter.finagle.util.TimerFromNettyTimer
import com.twitter.finagle.{SimpleFilter, Service}
import com.twitter.util._
import com.wikia.wikifactory._
import java.io.{FileInputStream, File}
import java.util.regex.PatternSyntaxException
import java.util.{NoSuchElementException, Calendar, Date}
import org.jboss.netty.handler.codec.http.HttpResponseStatus
import org.jboss.netty.util.HashedWheelTimer
import scala.Some
import scala.collection.JavaConversions._
import util.parsing.json.{JSONObject, JSONArray, JSONFormat}
import java.util.concurrent.TimeUnit

class ExceptionLogger[Req, Rep](val logger: NiceLogger) extends SimpleFilter[Req, Rep] {
	def this(loggerName: String) = this(NiceLogger(loggerName))
	def apply(request: Req, service: Service[Req, Rep]): Future[Rep] = {
		service(request).onFailure((exception) => {
			logger.exception("Exception in service", exception)
		})
	}
}

object Respond {
	def apply(content: String, status: HttpResponseStatus = Status.Ok, contentType: String = "text/plain; charset=utf-8") = {
		val response = Response(Version.Http11, status)
		response.contentType = contentType
		response.contentString = content
		response
	}
	def json(data: Iterable[DatabaseRuleInfo]) = {
		val jsonData = JSONArray(data.toList.map(x => x.toJSONObject))
		Respond(jsonData.toString(JSONFormat.defaultFormatter), Status.Ok, Message.ContentTypeJson)
	}
	def json(data: Map[String, DatabaseRuleInfo]) = {
		val jsonData = JSONObject(data.mapValues( x => x.toJSONObject))
		Respond(jsonData.toString(JSONFormat.defaultFormatter), Status.Ok, Message.ContentTypeJson)
	}

	def error(info: String, status: HttpResponseStatus = Status.InternalServerError) = Respond(info + "\n", status)
	val ok = Respond("ok\n")
	val failure = Respond("failure\n")
	val contentMissing = error("content parameter is missing")
	val unknownType = error("Unknown type parameter")
}


class MainService(val reloader: (Map[String, RuleSystem], Traversable[Int]) => Map[String, RuleSystem],
                  val scribe: Service[Map[String, Any], Unit], threadCount: Option[Int] = None) extends Service[Request, Response] {
	def this(initialRules: Map[String, RuleSystem], scribe: Service[Map[String, Any], Unit]) = this( (_, _) => initialRules, scribe)
	private val logger = NiceLogger("MainService")
	var nextExpireDate: Option[Date] = None
	var expireWatchTask: Option[TimerTask] = None
	val timer = new TimerFromNettyTimer(new HashedWheelTimer(1, TimeUnit.SECONDS))
	@transient var rules = reloader(Map.empty, Seq.empty)
	val threadPoolSize = threadCount.getOrElse(Runtime.getRuntime.availableProcessors()*2)
	val futurePool  = if (threadPoolSize <= 0) FuturePool.immediatePool else FuturePool(
		java.util.concurrent.Executors.newFixedThreadPool(threadPoolSize, new NamedPoolThreadFactory("MainService pool"))
	)
	watchExpired()

	override def close(deadline: Time) = {
		logger.trace("Stopping expired timer")
		timer.stop()
		logger.trace("Stopping scribe client")
		scribe.close(deadline)
	}
	def watchExpired() {
		val minDates = rules.values.flatMap(ruleSystem => ruleSystem.expiring.headOption.map(rule => rule.expires.get))
		expireWatchTask.map(task => {
			logger.trace("Old expire task " + task + "cancelled")
			task.cancel()
		})
		if (minDates.isEmpty) {
			nextExpireDate = None
			expireWatchTask = None
			logger.trace("Expire task not required")
		} else {
			val c = java.util.Calendar.getInstance(DB.dbTimeZone)
			c.setTime(minDates.min)
			c.set(Calendar.SECOND, 0)
			c.add(Calendar.MINUTE, 1)
			nextExpireDate = Some(c.getTime)
			logger.trace("Scheduling expire task at " + nextExpireDate.get)
			expireWatchTask = Some(timer.schedule(Time(nextExpireDate.get))(expire _))
		}
	}
	def expire() {
		val now = new Date().getTime
		val expired = rules.values.flatMap(ruleSystem => ruleSystem.expiring.takeWhile(rule => rule.expires.get.getTime <= now)).map(r => r.dbId)
		afterReload(expired)
	}
	def afterReload(expired: Traversable[Int]) {
		rules = reloader(rules, expired).toMap
		watchExpired()
	}
	type checkOrMatch = (Iterable[RuleSystem], Iterable[Checkable], Option[String], Option[Int]) => Response
	def validateRegex(request: Request) = {
		val s = request.params.getOrElse("regex", "")
		val response = try {
			s.r
			Respond.ok
		} catch {
			case e: PatternSyntaxException => Respond.failure
		}
		response
	}
	def reload(request: Request) = {
		val changed = request.getParam("changed", "").split(',').toSeq.filter(_ != "")
		val ids = if (changed.isEmpty) Seq.empty[Int]	else changed.map(_.toInt)
		afterReload(ids)
		Respond.ok
	}
	def humanReadableByteCount(bytes: Long): String = {
		val unit: Int = 1024
		if (bytes < unit) bytes + " B" else {
			val exp = (scala.math.log(bytes) / scala.math.log(unit)).toInt
			val pre = "KMGTPE".charAt(exp - 1) + "iB"
			f"${bytes / scala.math.pow(unit, exp)}%.1f $pre"
		}
	}
	def stats(request: Request): Response = Respond(statsString)
	def statsString: String = {
		val response =  (rules.toSeq.map(t => {
			val (s, ruleSystem) = t
			s + ":\n" + (ruleSystem.stats.map {
				"  " + _
			}.mkString("\n")) + "\n"
		}) ++ nextExpireDate.map("Next rule expire date: " + _.toString)
			++ sys.props.get("newrelic.environment").map("NewRelic environment: "+_)
			++ Seq(
		  Main.versionString,
		  "Worker threads: " + threadPoolSize,
			"Max memory: " + humanReadableByteCount(sys.runtime.maxMemory()),
		  "Free memory: " + humanReadableByteCount(sys.runtime.freeMemory()),
		  "Total memory: " + humanReadableByteCount(sys.runtime.totalMemory()),
		  ""
		)).mkString("\n")
		response
	}
	def viewRule(request: Request) : Response = {
		val id = request.params.getInt("id").get
		val found:Map[String,DatabaseRuleInfo] = rules.mapValues( rs => {	rs.rules.find( r => r.dbId == id) }).collect( x => x match {
			case (s:String, Some(rule:DatabaseRuleInfo)) => (s, rule)
		})
		Respond.json(found)
	}
	def stripPath(request: Request): String = {
		val requestPath = request.path
		logger.debug(s"${request.remoteHost} $requestPath ${request.params}")
		(if (requestPath.startsWith("http://")) {
			val afterPrefix = requestPath.substring("http://".length)
			afterPrefix.indexOf('/') match {
				case -1 => ""
				case x:Int => afterPrefix.substring(x+1)
			}
		} else {
			requestPath.stripPrefix("/")
		}).stripSuffix("/") // get rid of '/' at the end too
	}
	def apply(request: Request): Future[Response] = {
		stripPath(request) match {
			case "" => Future(Respond("PHALANX ALIVE"))
			case "match" => futurePool(ParsedRequest(request).matchResponse)
			case "check" => futurePool(ParsedRequest(request).checkResponse)
			case "validate" => futurePool(validateRegex(request))
			case "reload" => futurePool(reload(request))
			case "stats" => futurePool(stats(request))
			case "view" => futurePool(viewRule(request))
			case x => {
				logger.warn("Unknown request path: " + request.path + " [ " + x.toString + " ] ")
				Future(Respond.error("not found", Status.NotFound))
			}
		}
	}

	case class ParsedRequest(request: Request) {
		val params = request.params
		val lang = params.get("lang") match {
			case None => "en"
			case Some("") => "en"
			case Some(x) => x
		}
		val content = params.getAll("content").map(s => Checkable(s, lang))
		val user = params.get("user")
		val wiki = params.get("wiki").map(_.toInt)
		val checkTypes = params.getAll("type")
		val ruleSystems:Iterable[RuleSystem] = if (checkTypes.isEmpty) rules.values else
			try {
			checkTypes.map(s => rules(s)).toSet
			} catch {
				case _:NoSuchElementException => Set.empty
			}
		val combinations:Iterable[(RuleSystem, Checkable)] = (for (r <- ruleSystems; c <- content) yield (r, c))

		def findMatches(limit: Int):Seq[DatabaseRuleInfo] = {
			val matches = combinations.view.flatMap( (pair: (RuleSystem, Checkable)) => pair._1.allMatches(pair._2) )
			val result:Iterable[DatabaseRuleInfo] = (if (limit > 0) matches.take(limit).force else matches.force)
			result.headOption.map(sendToScribe(_))
			result.toSeq
		}
		def checkResponse = {
			if (ruleSystems.isEmpty) Respond.unknownType else
				if (content.isEmpty) Respond.contentMissing else {
				val matches = findMatches(1)
				logger.debug(s"check: lang=$lang user=$user wiki=$wiki checkTypes=$checkTypes content=$content matches=$matches")
				if (matches.isEmpty) Respond.ok else Respond.failure
				}
		}
		def matchResponse = {
			if (ruleSystems.isEmpty) Respond.unknownType else
				if (content.isEmpty) Respond.contentMissing else {
					val limit = request.params.getIntOrElse("limit", 1)
					val matches = findMatches(limit)
				  logger.debug(s"match: lang=$lang user=$user wiki=$wiki checkTypes=$checkTypes content=$content matches=$matches")
					Respond.json(matches)
				}
		}
		def sendToScribe(rule: DatabaseRuleInfo):Future[Unit] = {
			if (user.isDefined && wiki.isDefined)	scribe(Map(
				("blockId", rule.dbId) ,
				("blockType", rule.typeMask),
				("blockTs", com.wikia.wikifactory.DB.wikiCurrentTime),
				("blockUser", user.get),
				("city_id", wiki.get)
			)) else Future.Done
		}
	}
}

object Main extends App {
	val cfName: Option[String] = sys.props.get("phalanx.config") orElse {
		// load config from first config file that exists
		Seq(
			"phalanx.properties",
			"/usr/wikia/conf/current/phalanx.properties",
			"/usr/wikia/docroot/phalanx.properties",
			"phalanx.default.properties",
			"/usr/wikia/phalanx/phalanx.default.properties")
			.find(fileName => {
			val file = new File(fileName)
			file.exists() && file.canRead
		}
		)
	}

	def loadProperties(fileName: String): java.util.Properties = {
		val file = new File(fileName)
		val properties = new java.util.Properties()
		properties.load(new FileInputStream(file))
		println("Loaded properties from " + fileName)
		properties
	}
	def wikiaProp(key: String) = sys.props("com.wikia.phalanx." + key)
	def scribeClient() = {
		val host = wikiaProp("scribe.host")
		val port = wikiaProp("scribe.port").toInt
		new ScribeClient("log_phalanx", host, port)
	}

	cfName match {
		case Some(fileName) => sys.props ++= loadProperties(fileName).toMap
		case None => {
			println("Don't know where to load configuration from.")
			System.exit(2)
		}
	}

	val logger = NiceLogger("Main")
	val versionString = s"Phalanx server version ${PhalanxVersion.version}"
	logger.info(s"$versionString starting, properties loaded from ${cfName.get}")
	val scribe = {
		val scribetype = wikiaProp("scribe")
		logger.info("Creating scribe client (" + scribetype + ")")
		scribetype match {
			case "send" => scribeClient()
			case "buffer" => new ScribeBuffer(scribeClient(), Duration(wikiaProp("scribe.flushperiod").toInt, TimeUnit.MILLISECONDS ))
			case "discard" => new ScribeDiscard()
		}
	}

	val port = wikiaProp("port").toInt
	val threadCount:Option[Int] = wikiaProp("com.wikia.phalanx.threads") match {
		case s: String if (s != null && s.nonEmpty) => Some(s.toInt)
		case _ => None
	}

	val database = new DB(DB.DB_MASTER, None, "wikicities")
	logger.info("Connecting to database from configuration file "+database.config.sourcePath)
	val dbSession = database.connect()
	logger.info("Loading rules from database")
	val mainService = new MainService(
		(old, changed) => RuleSystemLoader.reloadSome(dbSession, old, changed.toSet),
		new ExceptionLogger(logger) andThen scribe,
	  threadCount
	)

	val config = ServerBuilder()
		.codec(RichHttp[Request](Http()))
		.name("Phalanx")
		.maxConcurrentRequests(Seq(20, mainService.threadPoolSize).max)
		.sendBufferSize(16*1024)
		.recvBufferSize(32*1024)
		.backlog(500)
		.bindTo(new java.net.InetSocketAddress(port))

	val preloaded = PackagePreloader(this.getClass, Seq(
		"com.wikia.phalanx",
		"com.twitter.finagle.http",
		"com.twitter.util"
	))
	logger.info("Preloaded "+preloaded.size+" classes")

	val server = config.build(ExceptionFilter andThen NewRelic andThen mainService)
	logger.info(s"Listening on port: $port")
	logger.trace("Initial stats: \n" + mainService.statsString)

	sys.addShutdownHook {
		logger.warn("Terminating")
		server.close(Duration(3, TimeUnit.SECONDS))
		mainService.close()
		logger.warn("Shutdown complete")
	}
}

