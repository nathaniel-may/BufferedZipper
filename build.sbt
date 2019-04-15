name         := "buffered-zipper"
version      := "0.1"
scalaVersion := "2.12.8"

import sbt.Tests.{Group, SubProcess}

lazy val root = (project in file("."))
  .settings(
    resolvers           ++= Build.resolvers,
    libraryDependencies ++= Build.dependencies,

    fork in Test := true,
    Test / testGrouping := groupByJavaAgent((definedTests in Test).value),
    tags in test += Tags.ForkedTestGroup -> 4 )

val home = System.getProperty("user.home")

val jammForkOpts = ForkOptions()
  .withRunJVMOptions(Vector(s"-javaagent:$home/.ivy2/cache/com.github.jbellis/jamm/jars/jamm-0.3.3.jar"))

def groupByJavaAgent(allTests: Seq[TestDefinition]) = allTests
  .groupBy(t => if(t.name.contains("NoJavaAgent")) ("NoJamm", ForkOptions())
                else                               ("Jamm",   jammForkOpts))
  .map { case ((gName, opts), tests) => Group(gName, tests, SubProcess(opts)) }
  .toSeq