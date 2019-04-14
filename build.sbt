name := "rand-pix"
version := "0.1"
scalaVersion := "2.12.8"

import sbt.Tests.{Group, SubProcess}

lazy val randpix = (project in file("."))
  .settings(
    resolvers           ++= Build.resolvers,
    libraryDependencies ++= Build.dependencies,

    fork in Test := true,
    testGrouping := groupByJavaAgent((definedTests in Test).value),
    tags in test += Tags.ForkedTestGroup -> 4
  )

val home = System.getProperty("user.home")

//val jammForkOpts = ForkOptions()
//  .withRunJVMOptions(Vector(s"-javaagent:$home/.ivy2/cache/com.github.jbellis/jamm/jars/jamm-0.3.3.jar"))

def groupByJavaAgent(allTests: Seq[TestDefinition]) = allTests
  .groupBy(t => t.name.contains("NoJavaAgent") || t.name.contains("no javaagent"))
  .map { case (true,  tests) => Group("NoAgent",   tests, SubProcess(ForkOptions()))
         case (false, tests) => Group("JammAgent", tests, SubProcess(ForkOptions())) } // TODO change to jammForkOpts
  .toSeq