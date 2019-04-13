name := "rand-pix"
version := "0.1"
scalaVersion := "2.12.8"

import Tests._

lazy val randpix = (project in file("."))
  .settings(
    resolvers += "jitpack" at "https://jitpack.io",

    libraryDependencies += "org.typelevel"      %% "cats-core"   % "1.5.0",
    libraryDependencies += "org.typelevel"      %% "cats-effect" % "1.1.0",
    libraryDependencies += "org.scalaz"         %% "scalaz-core" % "7.2.26",
    libraryDependencies += "com.github.jbellis" %  "jamm"        % "0.3.3",

    libraryDependencies += "com.github.nathaniel-may" %  "nest"        % "v0.1.0" % "test",
    libraryDependencies += "org.scalatest"            %% "scalatest"   % "3.0.5"  % "test",
    libraryDependencies += "org.scalacheck"           %% "scalacheck"  % "1.14.0" % "test",

    fork in Test := true,
    testGrouping := groupByJavaAgent((definedTests in Test).value),
    tags in test += Tags.ForkedTestGroup -> 4
  )

val home = System.getProperty("user.home")

val jammForkOpts = ForkOptions()
    .withRunJVMOptions(Vector(s"-javaagent:$home/.ivy2/cache/com.github.jbellis/jamm/jars/jamm-0.3.3.jar"))

def groupByJavaAgent(allTests: Seq[TestDefinition]) = allTests
  .groupBy(t => t.name.contains("NoJavaAgent") || t.name.contains("no javaagent"))
  .map { case (true,  tests) => Group("NoAgent",   tests, SubProcess(ForkOptions()))
         case (false, tests) => Group("JammAgent", tests, SubProcess(jammForkOpts)) }
  .toSeq