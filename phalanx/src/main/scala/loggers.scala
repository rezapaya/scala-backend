package com.wikia.phalanx

import org.slf4j.LoggerFactory
import com.twitter.util.{Future, Time}

case class NiceLogger(name: String) {
	val logger = LoggerFactory.getLogger(name)
	def trace(messageBlock: => String) {
		if (logger.isTraceEnabled) logger.trace(messageBlock)
	}
	def debug(messageBlock: => String) {
		if (logger.isDebugEnabled) logger.debug(messageBlock)
	}
	def info(messageBlock: => String) {
		if (logger.isInfoEnabled) logger.info(messageBlock)
	}
	def warn(messageBlock: => String) {
		if (logger.isWarnEnabled) logger.warn(messageBlock)
	}
	def error(messageBlock: => String) {
		if (logger.isErrorEnabled) logger.error(messageBlock)
	}
	def error(message: String, error: Throwable) {
		if (logger.isErrorEnabled) logger.error(message, error)
	}
	def timeIt[T](name:String)(func: => T):T = {
		if (logger.isTraceEnabled) {
			val start = Time.now
			val result = func
			val duration = Time.now - start
			logger.trace(name+" "+ duration.inMillis+"ms")
			result
		} else func
	}
	def timeIt[T](name:String, future: => Future[T]):Future[T] = {
		if (logger.isTraceEnabled) {
			val start = Time.now
			future.onSuccess( _ => {
				val duration = Time.now - start
				logger.trace(name+" "+ duration.inMillis+"ms")
			})
		} else future
	}
}
