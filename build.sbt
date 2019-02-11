name := "rand-pix"
version := "0.1"
scalaVersion := "2.12.8"

lazy val randpix = (project in file("."))
  .settings(
    resolvers += "jitpack" at "https://jitpack.io",

    libraryDependencies += "org.scala-lang.modules"   %%  "scala-xml"   % "1.1.1",
    libraryDependencies += "com.thoughtworks.binding" %%  "binding"     % "11.6.0",

    libraryDependencies += "org.typelevel"      %% "cats-core"   % "1.5.0",
    libraryDependencies += "org.typelevel"      %% "cats-effect" % "1.1.0",
    libraryDependencies += "org.scalaz"         %% "scalaz-core" % "7.2.26",
    libraryDependencies += "co.fs2"             %% "fs2-core"    % "1.0.2",
    libraryDependencies += "co.fs2"             %% "fs2-io"      % "1.0.2",
    libraryDependencies += "com.github.jbellis" %  "jamm"        % "0.3.3",

    libraryDependencies += "com.github.nathaniel-may" % "nest" % "v0.1.0" % "test",
    libraryDependencies += "org.scalatest"  %% "scalatest"   % "3.0.5"  % "test",
    libraryDependencies += "org.scalacheck" %% "scalacheck"  % "1.14.0" % "test"
  )