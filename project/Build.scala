import sbt._

/**
  * separate file for resolvers and dependencies. CI only refreshes from
  * cache when this file hash matches. Allows for changes in build.sbt
  * without rebuilding CI cache.
  */
object Build {
  val resolvers = Seq("jitpack" at "https://jitpack.io")

  val dependencies = Seq(
    "org.typelevel"      %% "cats-core"   % "1.5.0",
    "org.scalaz"         %% "scalaz-core" % "7.2.26",
    "com.github.jbellis" %  "jamm"        % "0.3.3",

    "org.typelevel"     %% "cats-effect"     % "1.2.0"  % "test",
    "io.chrisdavenport" %% "cats-scalacheck" % "0.1.1"  % "test",
    "org.scalatest"     %% "scalatest"       % "3.0.5"  % "test",
    "org.scalacheck"    %% "scalacheck"      % "1.14.0" % "test" )
}
