name := "zookeeper-client"

organization := "com.loopfor.zookeeper"

version := "1.1-SNAPSHOT"

description := "Scala API for ZooKeeper"

homepage := Some(url("https://github.com/davidledwards/zookeeper"))

licenses := Seq("Apache License, Version 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))

scmInfo <<= version { v =>
  Some(ScmInfo(
    url("https://github.com/davidledwards/zookeeper/tree/release-" + v + "/zookeeper-client"),
    "scm:git:https://github.com/davidledwards/zookeeper.git",
    Some("scm:git:https://github.com/davidledwards/zookeeper.git")
  ))
}

scalaVersion := "2.10.2"

scalacOptions ++= Seq(
  "-deprecation",
  "-unchecked",
  "-feature",
  "-encoding", "UTF-8"
)

javacOptions ++= Seq(
  "-source", "1.6",
  "-target", "1.6"
)

// Compile dependencies.
libraryDependencies ++= Seq(
  "org.apache.zookeeper" % "zookeeper" % "3.4.5"
    exclude("jline", "jline"),
  "log4j" % "log4j" % "1.2.16"
    exclude("javax.jms", "jms")
)

// Test dependencies.
libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "1.9.1" % "test"
)

publishMavenStyle := true

publishArtifact in Test := false

publishTo <<= version { v =>
  val repo = if (v endsWith "SNAPSHOT")
    "Sonatype Nexus Snapshot Repository" at "https://oss.sonatype.org/content/repositories/snapshots/"
  else
    "Sonatype Nexus Release Repository" at "https://oss.sonatype.org/service/local/staging/deploy/maven2/"
  Some(repo)
}

// Ensures that published POM has no repository dependencies.
pomIncludeRepository := { _ => false }

pomExtra := (
  <developers>
    <developer>
      <id>davidledwards</id>
      <name>David Edwards</name>
      <email>david.l.edwards@gmail.com</email>
    </developer>
  </developers>
)