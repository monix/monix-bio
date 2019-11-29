val compilerOptions = Seq(
  "-deprecation",
  "-encoding",
  "UTF-8",
  "-feature",
  "-language:existentials",
  "-language:higherKinds",
  "-unchecked",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen"
)

lazy val `monix-bio` = project.in(file("."))
  .settings(commonSettings, releaseSettings, skipOnPublishSettings)
  .aggregate(core)

lazy val core = project.in(file("core"))
  .settings(commonSettings, releaseSettings)
  .settings(
    name := "monix-bio"
  )

lazy val contributors = Seq(
  "Avasil" -> "Piotr Gawrys"
)

val monixVersion = "3.1.0"
val minitestVersion = "2.7.0"

def priorTo2_13(scalaVersion: String): Boolean =
  CrossVersion.partialVersion(scalaVersion) match {
    case Some((2, minor)) if minor < 13 => true
    case _                              => false
  }

// General Settings
lazy val commonSettings = Seq(
  organization := "io.monix",

  scalaVersion := "2.13.1",
  crossScalaVersions := Seq("2.11.12", "2.12.10", "2.13.1"),
  scalacOptions ++= compilerOptions,
  scalacOptions ++= (
    if (priorTo2_13(scalaVersion.value))
      Seq(
        "-Xfuture",
        "-Yno-adapted-args",
        "-Ywarn-unused-import"
      )
    else
      Seq(
        "-Ywarn-unused:imports"
      )
    ),

  addCompilerPlugin("org.typelevel" % "kind-projector" % "0.11.0" cross CrossVersion.full),
  testFrameworks := Seq(new TestFramework("minitest.runner.Framework")),
  libraryDependencies ++= Seq(
    "io.monix" %% "monix-catnap" % monixVersion,
    "io.monix" %% "minitest" % minitestVersion % Test,
    "io.monix" %% "minitest-laws" % minitestVersion % Test
  )
)

lazy val releaseSettings = {
  import ReleaseTransformations._
  Seq(
    releaseCrossBuild := true,
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runClean,
      runTest,
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      // For non cross-build projects, use releaseStepCommand("publishSigned")
      releaseStepCommandAndRemaining("+publishSigned"),
      setNextVersion,
      commitNextVersion,
      releaseStepCommand("sonatypeReleaseAll"),
      pushChanges
    ),
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (isSnapshot.value)
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases" at nexus + "service/local/staging/deploy/maven2")
    },
    credentials ++= (
      for {
        username <- Option(System.getenv().get("SONATYPE_USERNAME"))
        password <- Option(System.getenv().get("SONATYPE_PASSWORD"))
      } yield
        Credentials(
          "Sonatype Nexus Repository Manager",
          "oss.sonatype.org",
          username,
          password
        )
    ).toSeq,
    publishArtifact in Test := false,
    releasePublishArtifactsAction := PgpKeys.publishSigned.value,
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/monix/monix-bio"),
        "git@github.com:monix/monix-bio.git"
      )
    ),
    homepage := Some(url("https://github.com/monix/monix-bio")),
    licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
    publishMavenStyle := true,
    pomIncludeRepository := { _ =>
      false
    },
    pomExtra := {
      <developers>
        {for ((username, name) <- contributors) yield
        <developer>
          <id>{username}</id>
          <name>{name}</name>
          <url>http://github.com/{username}</url>
        </developer>
        }
      </developers>
    },
      headerLicense := Some(HeaderLicense.Custom(
      """|Copyright (c) 2019-2019 by The Monix Project Developers.
         |See the project homepage at: https://monix.io
         |
         |Licensed under the Apache License, Version 2.0 (the "License");
         |you may not use this file except in compliance with the License.
         |You may obtain a copy of the License at
         |
         |    http://www.apache.org/licenses/LICENSE-2.0
         |
         |Unless required by applicable law or agreed to in writing, software
         |distributed under the License is distributed on an "AS IS" BASIS,
         |WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
         |See the License for the specific language governing permissions and
         |limitations under the License."""
        .stripMargin)),
  )
}


lazy val skipOnPublishSettings = Seq(
  skip in publish := true,
  publish := (()),
  publishLocal := (()),
  publishArtifact := false,
  publishTo := None
)
