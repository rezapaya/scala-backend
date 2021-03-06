name := "utils"

organization := "com.wikia"

version := "0.3"

scalaVersion := "2.10.1"

publishMavenStyle := true

publishTo <<= (version) { version: String =>
	val repoInfo = if( version.trim.endsWith( "SNAPSHOT" ) ) {
			( "Wikia Maven repository (snapshots)" -> "/srv/maven/snapshots" )
		}
		else {
			( "Wikia Maven repository (releases)" -> "/srv/maven/releases" )
		}
	val user = System.getProperty("user.name")
 	val keyFile = (Path.userHome / ".ssh" / "id_rsa").asFile
	Some( Resolver.ssh( repoInfo._1, "pkg-s1.wikia-prod", repoInfo._2 ) as ( user, keyFile ) withPermissions( "0644" ) )
}

publishArtifact in (Compile, packageDoc) := false

resolvers ++= Seq(
	"Wikia Maven repository" at "http://pkg-s1.wikia-prod/maven/releases/"
)

libraryDependencies ++= Seq(
  "com.twitter" % "util-core_2.10" % "6.1.0",
  "org.scalatest" % "scalatest_2.10" % "1.9.1" % "test"
)
