import sbt.url

import scala.xml.Elem
import scala.xml.transform.{RewriteRule, RuleTransformer}

addCommandAlias("ci-js",       s";clean ;coreJS/test")
addCommandAlias("ci-jvm",      s";clean ;benchmarks/compile ;coreJVM/test")

val monixVersion = "3.1.0"
val minitestVersion = "2.7.0"
val catsVersion = "2.0.0"
val catsEffectVersion = "2.0.0"

lazy val `monix-bio` = project.in(file("."))
  .enablePlugins(ScalaUnidocPlugin)
  .settings(skipOnPublishSettings)
  .aggregate(coreJVM, coreJS)
  .settings(unidocSettings)
  .settings(sharedSettings)

lazy val coreJVM = project.in(file("core/jvm"))
  .settings(crossSettings ++ crossVersionSharedSources)
  .settings(name := "monix-bio")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "io.monix" %% "monix-catnap" % monixVersion,
      "io.monix" %% "minitest" % minitestVersion % Test,
      "io.monix" %% "minitest-laws" % minitestVersion % Test,
      "org.typelevel" %% "cats-effect-laws" % catsEffectVersion % Test
    ),
  )

lazy val coreJS = project.in(file("core/js"))
  .settings(crossSettings ++ crossVersionSharedSources)
  .enablePlugins(AutomateHeaderPlugin)
  .enablePlugins(ScalaJSPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "io.monix" %%% "monix-catnap" % monixVersion,
      "io.monix" %%% "minitest" % minitestVersion % Test,
      "io.monix" %%% "minitest-laws" % minitestVersion % Test,
      "org.typelevel" %%% "cats-effect-laws" % catsEffectVersion % Test
    ),
  )
  .settings(scalaJSSettings)
  .settings(name := "monix-bio")

lazy val benchmarks = project.in(file("benchmarks"))
  .dependsOn(coreJVM)
  .enablePlugins(JmhPlugin)
  .settings(doNotPublishArtifact)
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "1.0.0-RC17",
      "io.monix" %% "monix-eval" % "3.1.0"
    ))

/**
  * Configures generated API documentation website.
  */
lazy val unidocSettings = Seq(
  unidocProjectFilter in (ScalaUnidoc, unidoc) :=
    inProjects(coreJVM),

  // Exclude monix.*.internal from ScalaDoc
  sources in (ScalaUnidoc, unidoc) ~= (_ filterNot { file =>
    // Exclude all internal Java files from documentation
    file.getCanonicalPath matches "^.*monix.+?internal.*?\\.java$"
  }),

  scalacOptions in (ScalaUnidoc, unidoc) +=
    "-Xfatal-warnings",
  scalacOptions in (ScalaUnidoc, unidoc) --=
    Seq("-Ywarn-unused-import", "-Ywarn-unused:imports"),
  scalacOptions in (ScalaUnidoc, unidoc) ++=
    Opts.doc.title(s"Monix BIO"),
  scalacOptions in (ScalaUnidoc, unidoc) ++=
    Opts.doc.sourceUrl(s"https://github.com/monix/monix-bio/tree/v${version.value}€{FILE_PATH}.scala"),
  scalacOptions in (ScalaUnidoc, unidoc) ++=
    Seq("-doc-root-content", file("rootdoc.txt").getAbsolutePath),
  scalacOptions in (ScalaUnidoc, unidoc) ++=
    Opts.doc.version(s"${version.value}")
)

lazy val contributors = Seq(
  "Avasil" -> "Piotr Gawrys"
)

lazy val doNotPublishArtifact = Seq(
  publishArtifact := false,
  publishArtifact in (Compile, packageDoc) := false,
  publishArtifact in (Compile, packageSrc) := false,
  publishArtifact in (Compile, packageBin) := false
)

// General Settings
lazy val sharedSettings = Seq(
  organization := "io.monix",
  scalaVersion := "2.13.1",
  crossScalaVersions := Seq("2.12.10", "2.13.1"),
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
    case _ =>
      Seq(
        "-Ywarn-unused:imports",
        // Replaces macro-paradise in Scala 2.13
        "-Ymacro-annotations"
      )
  }),

  // Linter
  scalacOptions ++= Seq(
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
  ),

  scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, majorVersion)) if majorVersion <= 12 =>
      Seq(
        "-Xlint:inaccessible", // warn about inaccessible types in method signatures
        "-Xlint:by-name-right-associative", // By-name parameter of right associative operator
        "-Xlint:unsound-match" // Pattern match may not be typesafe
      )
    case _ =>
      Seq.empty
  }),

  // Silence all warnings from src_managed files
  scalacOptions += "-P:silencer:pathFilters=.*[/]src_managed[/].*",
  // Turning off fatal warnings for ScalaDoc, otherwise we can't release.
  scalacOptions in (Compile, doc) ~= (_ filterNot (_ == "-Xfatal-warnings")),

  addCompilerPlugin("org.typelevel" % "kind-projector" % "0.11.0" cross CrossVersion.full),
  addCompilerPlugin("com.github.ghik" % "silencer-plugin" % "1.6.0" cross CrossVersion.full),

  // ScalaDoc settings
  autoAPIMappings := true,
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

  credentials += Credentials(
    "Sonatype Nexus Repository Manager",
    "oss.sonatype.org",
    sys.env.getOrElse("SONATYPE_USER", ""),
    sys.env.getOrElse("SONATYPE_PASS", "")
  ),

  publishMavenStyle := true,
  publishTo := Some(
    if (isSnapshot.value)
      Opts.resolver.sonatypeSnapshots
    else
      Opts.resolver.sonatypeStaging
  ),
  isSnapshot := version.value endsWith "SNAPSHOT",
  publishArtifact in Test := false,
  pomIncludeRepository := { _ => false }, // removes optional dependencies

  // For evicting Scoverage out of the generated POM
  // See: https://github.com/scoverage/sbt-scoverage/issues/153
  pomPostProcess := { (node: xml.Node) =>
    new RuleTransformer(new RewriteRule {
      override def transform(node: xml.Node): Seq[xml.Node] = node match {
        case e: Elem
          if e.label == "dependency" && e.child.exists(child => child.label == "groupId" && child.text == "org.scoverage") => Nil
        case _ => Seq(node)
      }
    }).transform(node).head
  },

  licenses := Seq("APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
  homepage := Some(url("https://monix.io")),

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

  developers := List(
    Developer(
      id="Avasil",
      name="Piotr Gawrys",
      email="pgawrys2@gmail.com",
      url=url("https://github.com/Avasil")
    ))
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

lazy val crossVersionSharedSources: Seq[Setting[_]] =
  Seq(Compile, Test).map { sc =>
    (unmanagedSourceDirectories in sc) ++= {
      (unmanagedSourceDirectories in sc).value.flatMap { dir =>
        Seq(
          scalaPartV.value match {
            case Some((2, y)) if y == 11 => new File(dir.getPath + "_2.11")
            case Some((2, y)) if y == 12 => new File(dir.getPath + "_2.12")
            case Some((2, y)) if y >= 13 => new File(dir.getPath + "_2.13")
          },

          scalaPartV.value match {
            case Some((2, n)) if n >= 12 => new File(dir.getPath + "_2.12+")
            case _                       => new File(dir.getPath + "_2.12-")
          },

          scalaPartV.value match {
            case Some((2, n)) if n >= 13 => new File(dir.getPath + "_2.13+")
            case _                       => new File(dir.getPath + "_2.13-")
          },
        )
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

lazy val docsMappingsAPIDir =
  settingKey[String]("Name of subdirectory in site target directory for api docs")

lazy val microsite = project
  .in(file("docs"))
  .enablePlugins(MicrositesPlugin, MdocPlugin)
  .settings(sharedSettings)
  .settings(skipOnPublishSettings)
  .dependsOn(coreJVM)
  .settings {
    import microsites._
    Seq(
      micrositeName := "Monix BIO",
      micrositeDescription := "Monix Bifunctor IO implementation",
      micrositeAuthor := "Monix Developers",
      micrositeTwitterCreator := "@Avasil",
      micrositeGithubOwner := "monix",
      micrositeGithubRepo := "monix/monix-bio",
      micrositeUrl := "https://monix-bio.github.io/",
      micrositeBaseUrl := "/",
      micrositeDocumentationUrl := "https://monix-bio.github.io/",
      micrositeGitterChannelUrl := "monix/monix-bio",
      micrositeFooterText := None,
      micrositeHighlightTheme := "atom-one-light",
      micrositePalette := Map(
        "brand-primary" -> "#3e5b95",
        "brand-secondary" -> "#294066",
        "brand-tertiary" -> "#2d5799",
        "gray-dark" -> "#49494B",
        "gray" -> "#7B7B7E",
        "gray-light" -> "#E5E5E6",
        "gray-lighter" -> "#F4F3F4",
        "white-color" -> "#FFFFFF"
      ),
      micrositeCompilingDocsTool := WithMdoc,
      fork in mdoc := true,
      libraryDependencies += "com.47deg" %% "github4s" % "0.21.0",
      micrositePushSiteWith := GitHub4s,
      micrositeGithubToken := sys.env.get("GITHUB_TOKEN"),
      micrositeExtraMdFiles := Map(
        file("CODE_OF_CONDUCT.md") -> ExtraMdFileConfig("CODE_OF_CONDUCT.md", "page", Map("title" -> "Code of Conduct",   "section" -> "code of conduct", "position" -> "100")),
        file("LICENSE.md") -> ExtraMdFileConfig("LICENSE.md", "page", Map("title" -> "License",   "section" -> "license",   "position" -> "101"))
      ),
      docsMappingsAPIDir := s"api",
      addMappingsToSiteDir(mappings in (ScalaUnidoc, packageDoc) in `monix-bio`, docsMappingsAPIDir),
      sourceDirectory in Compile := baseDirectory.value / "src",
      sourceDirectory in Test := baseDirectory.value / "test",
      mdocIn := (sourceDirectory in Compile).value / "mdoc",

      // Bug in sbt-microsites
      micrositeConfigYaml := microsites.ConfigYml(
        yamlCustomProperties = Map("exclude" -> List.empty[String])
      ),
    )
  }

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