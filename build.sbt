name := "rand-pix"
version := "0.1"
scalaVersion := "2.12.8"

lazy val randpix = (project in file("."))
  .settings(
    libraryDependencies += "org.typelevel"  %% "cats-core"   % "1.5.0",
    libraryDependencies += "org.typelevel"  %% "cats-effect" % "1.1.0",
    libraryDependencies += "org.scalaz"     %% "scalaz-core" % "7.2.26",
    libraryDependencies += "co.fs2"         %% "fs2-core"    % "1.0.2",
    libraryDependencies += "co.fs2"         %% "fs2-io"      % "1.0.2",
    libraryDependencies += "org.scalatest"  %% "scalatest"   % "3.0.5"  % "test",
    libraryDependencies += "org.scalacheck" %% "scalacheck"  % "1.14.0" % "test"
  )