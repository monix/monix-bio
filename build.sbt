import sbt.url

addCommandAlias("ci-js",       s";clean ;coreJS/test")
addCommandAlias("ci-jvm",      s";clean ;benchmarks/compile ;coreJVM/test ;tracingTests/test")
addCommandAlias("ci-jvm-mima", s";coreJVM/mimaReportBinaryIssues")

inThisBuild(List(
  organization := "io.monix",
  homepage := Some(url("https://monix.io")),
  licenses := Seq("APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
  developers := List(
    Developer(
      id="Avasil",
      name="Piotr Gawrys",
      email="pgawrys2@gmail.com",
      url=url("https://github.com/Avasil")
    ))
))

val monixVersion = "3.4.0"
val minitestVersion = "2.9.6"
val catsEffectVersion = "2.5.1"

// The Monix version with which we must keep binary compatibility.
// https://github.com/typesafehub/migration-manager/wiki/Sbt-plugin
val monixSeries = "1.0.0"

lazy val `monix-bio` = project.in(file("."))
  .settings(skipOnPublishSettings)
  .aggregate(coreJVM, coreJS)
  .settings(sharedSettings)

lazy val coreJVM = project.in(file("core/jvm"))
  .settings(crossSettings ++ crossVersionSourcesSettings)
  .settings(name := "monix-bio")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(doctestTestSettings)
  .settings(mimaSettings)

lazy val coreJS = project.in(file("core/js"))
  .settings(crossSettings ++ crossVersionSourcesSettings)
  .enablePlugins(AutomateHeaderPlugin)
  .enablePlugins(ScalaJSPlugin)
  .settings(scalaJSSettings)
  .settings(name := "monix-bio")
 
lazy val benchmarks = project.in(file("benchmarks"))
  .dependsOn(coreJVM)
  .enablePlugins(JmhPlugin)
  .settings(doNotPublishArtifact)
  .settings(crossSettings)
  .settings(
    scalaVersion := "2.12.13",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "1.0.0",
      "io.monix" %% "monix-eval" % monixVersion
    ))

lazy val docs = project
  .in(file("monix-bio-docs"))
  .settings(
    moduleName := "monix-bio-docs",
    name := moduleName.value,
    sharedSettings,
    skipOnPublishSettings,
    mdocSettings
  )
  .dependsOn(coreJVM)
  .enablePlugins(DocusaurusPlugin, MdocPlugin, ScalaUnidocPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "1.0.0-RC21-1",
      "dev.zio" %% "zio-interop-cats" % "2.1.3.0-RC16",
      "io.monix" %% "monix-eval" % monixVersion
    ))

// monix-tracing-tests (not published)

lazy val FullTracingTest = config("fulltracing").extend(Test)

lazy val tracingTests = project.in(file("tracingTests"))
  .dependsOn(coreJVM % "compile->compile; test->test")
  .settings(sharedSettings)
  .configs(FullTracingTest)
  .settings(testFrameworks := Seq(new TestFramework("minitest.runner.Framework")))
  .settings(inConfig(FullTracingTest)(Defaults.testSettings): _*)
  .settings(
    unmanagedSourceDirectories in FullTracingTest += {
      baseDirectory.value.getParentFile / "src" / "fulltracing" / "scala"
    },
    test in Test := (test in Test).dependsOn(test in FullTracingTest).value,
    fork in Test := true,
    fork in FullTracingTest := true,
    javaOptions in Test ++= Seq(
      "-Dmonix.bio.enhancedExceptions=true",
      "-Dmonix.bio.stackTracingMode=cached"
    ),
    javaOptions in FullTracingTest ++= Seq(
      "-Dmonix.bio.enhancedExceptions=true",
      "-Dmonix.bio.stackTracingMode=full"
    )
  )

lazy val mdocSettings = Seq(
  scalacOptions --= Seq("-Xfatal-warnings", "-Ywarn-unused"),
  crossScalaVersions := Seq(scalaVersion.value),
  unidocProjectFilter in (ScalaUnidoc, unidoc) := inProjects(coreJVM),
  target in (ScalaUnidoc, unidoc) := (baseDirectory in LocalRootProject).value / "website" / "static" / "api",
  cleanFiles += (target in (ScalaUnidoc, unidoc)).value,
  docusaurusCreateSite := docusaurusCreateSite
    .dependsOn(unidoc in Compile)
    .dependsOn(updateSiteVariables in ThisBuild)
    .value,
  docusaurusPublishGhpages :=
    docusaurusPublishGhpages
      .dependsOn(unidoc in Compile)
      .dependsOn(updateSiteVariables in ThisBuild)
      .value,
  scalacOptions in (ScalaUnidoc, unidoc) ++= Seq(
    "-doc-source-url", s"https://github.com/monix/monix-bio/tree/v${version.value}€{FILE_PATH}.scala",
    "-sourcepath", baseDirectory.in(LocalRootProject).value.getAbsolutePath,
    "-doc-title", "Monix BIO",
    "-doc-version", s"v${version.value}",
    "-groups"
  ),
  // Exclude monix.*.internal from ScalaDoc
  sources in (Compile, doc) ~= (_ filterNot { file =>
    // Exclude all internal Java files from documentation
    file.getCanonicalPath matches "^.*monix.+?internal.*?\\.java$"
  }),
)

def minorVersion(version: String): String = {
  val (major, minor) =
    CrossVersion.partialVersion(version).get
  s"$major.$minor"
}

val updateSiteVariables = taskKey[Unit]("Update site variables")
updateSiteVariables in ThisBuild := {
  val file =
    (baseDirectory in LocalRootProject).value / "website" / "variables.js"

  val variables =
    Map[String, String](
      "organization" -> (organization in LocalRootProject).value,
      "coreModuleName" -> (moduleName in coreJVM).value,
      "latestVersion" -> version.value.takeWhile(_ != '+'),
      "scalaPublishVersions" -> {
        val minorVersions = (crossScalaVersions in coreJVM).value.map(minorVersion)
        if (minorVersions.size <= 2) minorVersions.mkString(" and ")
        else minorVersions.init.mkString(", ") ++ " and " ++ minorVersions.last
      }
    )

  val fileHeader =
    "// Generated by sbt. Do not edit directly."

  val fileContents =
    variables.toList
      .sortBy { case (key, _) => key }
      .map { case (key, value) => s"  $key: '$value'" }
      .mkString(s"$fileHeader\nmodule.exports = {\n", ",\n", "\n};\n")

  IO.write(file, fileContents)
}

lazy val contributors = Seq(
  "Avasil" -> "Piotr Gawrys"
)

lazy val doNotPublishArtifact = Seq(
  publishArtifact := false,
  publishArtifact in (Compile, packageDoc) := false,
  publishArtifact in (Compile, packageSrc) := false,
  publishArtifact in (Compile, packageBin) := false
)

lazy val isDotty =
  Def.setting {
    scalaPartV.value match {
      case Some((3, _)) => true
      case _ => false
    }
  }

// General Settings
lazy val sharedSettings = Seq(
  scalaVersion := "2.13.5",
  crossScalaVersions := Seq("2.12.13", "2.13.5", "3.0.0"),
  scalacOptions ++= Seq(
    // warnings
    "-unchecked", // able additional warnings where generated code depends on assumptions
    "-deprecation", // emit warning for usages of deprecated APIs
    "-feature", // emit warning usages of features that should be imported explicitly
    // Features enabled by default
    "-language:higherKinds",
    "-language:implicitConversions",
    "-language:experimental.macros",
  ),
  // More version specific compiler options
  scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, v)) if v <= 12 =>
      Seq(
        "-Ypartial-unification",
        "-Xfuture",
        "-Yno-adapted-args",
        "-Ywarn-unused-import"
      )
    case Some((2, v)) =>
      Seq(
        "-Ywarn-unused:imports",
        // Replaces macro-paradise in Scala 2.13
        "-Ymacro-annotations",

      )
    case _ => Seq()
  }),

  // Linter
  scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, _)) =>
      Seq(
        // Turns all warnings into errors ;-)
        //    "-Xfatal-warnings",
        // Enables linter options
        "-Xlint:adapted-args", // warn if an argument list is modified to match the receiver
        "-Xlint:nullary-unit", // warn when nullary methods return Unit
        "-Xlint:nullary-override", // warn when non-nullary `def f()' overrides nullary `def f'
        "-Xlint:infer-any", // warn when a type argument is inferred to be `Any`
        "-Xlint:missing-interpolator", // a string literal appears to be missing an interpolator id
        "-Xlint:doc-detached", // a ScalaDoc comment appears to be detached from its element
        "-Xlint:private-shadow", // a private field (or class parameter) shadows a superclass field
        "-Xlint:type-parameter-shadow", // a local type parameter shadows a type already in scope
        "-Xlint:poly-implicit-overload", // parameterized overloaded implicit methods are not visible as view bounds
        "-Xlint:option-implicit", // Option.apply used implicit view
        "-Xlint:delayedinit-select", // Selecting member of DelayedInit
        "-Xlint:package-object-classes", // Class or object defined in package object
      )
    case _ => Seq()
  }),

  scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, majorVersion)) if majorVersion <= 12 =>
      Seq(
        "-Xlint:inaccessible", // warn about inaccessible types in method signatures
        "-Xlint:by-name-right-associative", // By-name parameter of right associative operator
        "-Xlint:unsound-match" // Pattern match may not be typesafe
      )
    case Some((3, _)) =>
      Seq(
        "-Ykind-projector"
      )
    case _ =>
      Seq.empty
  }),
  // Turning off fatal warnings for ScalaDoc, otherwise we can't release.
  scalacOptions in (Compile, doc) ~= (_ filterNot (_ == "-Xfatal-warnings")),

  libraryDependencies ++= {
    if (isDotty.value)
      Seq()
    else
      Seq(
        compilerPlugin("org.typelevel" % "kind-projector" % "0.12.0" cross CrossVersion.full)
      )
  },

  libraryDependencies ++= Seq(
    "io.monix" %%% "monix-catnap" % monixVersion,
    "io.monix" %%% "minitest" % minitestVersion % Test,
    "io.monix" %%% "minitest-laws" % minitestVersion % Test,
    "org.typelevel" %%% "cats-effect-laws" % catsEffectVersion % Test
  ),

  // ScalaDoc settings
  autoAPIMappings := true,
  apiURL := Some(url("https://bio.monix.io/api/monix/bio/index.html")),
  apiMappings ++= {
    val cp: Seq[Attributed[File]] = (fullClasspath in Compile).value
    def findManagedDependency(organization: String, name: String): File = {
      ( for {
        entry <- cp
        module <- entry.get(moduleID.key)
        if module.organization == organization
        if module.name.startsWith(name)
      } yield entry.data
        ).head
    }
    Map(
      findManagedDependency("io.monix","monix-execution") -> url("https://monix.io/api/3.1/"),
      findManagedDependency("io.monix","monix-catnap") -> url("https://monix.io/api/3.1/"),
      findManagedDependency("org.typelevel","cats-effect") -> url("https://typelevel.org/cats-effect/api/")
    )
  },

  scalacOptions in ThisBuild ++= Seq(
    // Note, this is used by the doc-source-url feature to determine the
    // relative path of a given source file. If it's not a prefix of a the
    // absolute path of the source file, the absolute path of that file
    // will be put into the FILE_SOURCE variable, which is
    // definitely not what we want.
    "-sourcepath", file(".").getAbsolutePath.replaceAll("[.]$", "")
  ),

  resolvers ++= Seq(
    "Typesafe Releases" at "https://repo.typesafe.com/typesafe/releases",
    Resolver.sonatypeRepo("releases")
  ),

  // https://github.com/sbt/sbt/issues/2654
  incOptions := incOptions.value.withLogRecompileOnMacro(false),

  // -- Settings meant for deployment on oss.sonatype.org
  sonatypeProfileName := organization.value,

  isSnapshot := version.value endsWith "SNAPSHOT",
  publishArtifact in Test := false,
  pomIncludeRepository := { _ => false }, // removes optional dependencies

  testFrameworks := Seq(new TestFramework("minitest.runner.Framework")),
  headerLicense := Some(HeaderLicense.Custom(
    """|Copyright (c) 2019-2020 by The Monix Project Developers.
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

  scmInfo := Some(
    ScmInfo(
      url("https://github.com/monix/monix-bio"),
      "scm:git@github.com:monix/monix-bio.git"
    )),
)

lazy val crossSettings = sharedSettings ++ Seq(
  unmanagedSourceDirectories in Compile += {
    baseDirectory.value.getParentFile / "shared" / "src" / "main" / "scala"
  },
  unmanagedSourceDirectories in Test += {
    baseDirectory.value.getParentFile / "shared" / "src" / "test" / "scala"
  }
)

def scalaPartV = Def setting (CrossVersion partialVersion scalaVersion.value)

lazy val crossVersionSourcesSettings: Seq[Setting[_]] =
  Seq(Compile, Test).map { sc =>
    (sc / unmanagedSourceDirectories) ++= {
      (sc / unmanagedSourceDirectories).value.flatMap { dir =>
        scalaPartV.value match {
          case Some((2, 12)) => Seq(new File(dir.getPath + "_2.13-"), new File(dir.getPath + "_3.0-"))
          case Some((3, _))  => Seq(new File(dir.getPath + "_3.0"))
          case _             => Seq(new File(dir.getPath + "_2.13+"), new File(dir.getPath + "_3.0-"))
        }
      }
    }
  }

lazy val scalaJSSettings = Seq(
  // Use globally accessible (rather than local) source paths in JS source maps
  scalacOptions += {
    val tagOrHash =
      if (isSnapshot.value) git.gitHeadCommit.value.get
      else s"v${git.baseVersion.value}"
    val l = (baseDirectory in LocalRootProject).value.toURI.toString
    val g = s"https://raw.githubusercontent.com/monix/monix-bio/$tagOrHash/"
    s"-P:scalajs:mapSourceURI:$l->$g"
  }
)

val mimaSettings = Seq(
  mimaPreviousArtifacts := Set("io.monix" %% "monix-bio" % monixSeries),
  mimaBinaryIssueFilters ++= MimaFilters.changes
)
// https://github.com/lightbend/mima/pull/289
mimaFailOnNoPrevious in ThisBuild := false

lazy val docsMappingsAPIDir =
  settingKey[String]("Name of subdirectory in site target directory for api docs")

lazy val doctestTestSettings = Seq(
  doctestTestFramework := DoctestTestFramework.Minitest,
  doctestIgnoreRegex := Some(s".*BIOApp.scala"),
  doctestOnlyCodeBlocksMode := true
)

lazy val skipOnPublishSettings = Seq(
  skip in publish := true,
  publish := (()),
  publishLocal := (()),
  publishArtifact := false,
  publishTo := None
)

/* The BaseVersion setting represents the in-development (upcoming) version,
 * as an alternative to SNAPSHOTS.
 */
git.baseVersion := (version in ThisBuild).value
