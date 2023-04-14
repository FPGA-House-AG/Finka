ThisBuild / version := "1.0"
ThisBuild / scalaVersion := "2.11.12"
ThisBuild / organization := "online.blackwire"

val spinalVersion = "1.8.1"

lazy val finka = (project in file("."))
  .settings(
    libraryDependencies ++= Seq(
      "com.github.spinalhdl" % "spinalhdl-core_2.11" % spinalVersion,
      "com.github.spinalhdl" % "spinalhdl-lib_2.11" % spinalVersion,
      "com.github.spinalhdl" % "spinalhdl-sim_2.11" % spinalVersion,
      compilerPlugin("com.github.spinalhdl" % "spinalhdl-idsl-plugin_2.11" % spinalVersion),
      "org.scalatest" %% "scalatest" % "3.2.5",
      "org.yaml" % "snakeyaml" % "1.8"
    ),
    name := "Finka",
    Compile / scalaSource := baseDirectory.value / "hardware" / "scala"
    //Test / scalaSource := baseDirectory.value / "test" / "scala"
  )
  .dependsOn(vexRiscv)
  .dependsOn(spinalCorundum)
  .dependsOn(blackwireSpinal)

lazy val vexRiscv = RootProject(uri("https://github.com/SpinalHDL/VexRiscv.git#c52433575dec04f10063b2fd7cebd0545c8b1be9"))
//lazy val vexRiscv = RootProject(file("../VexRiscv.pinned"))

lazy val spinalCorundum = ProjectRef(file("../SpinalCorundum"), "spinalCorundum")
lazy val blackwireSpinal = ProjectRef(file("../BlackwireSpinal"), "blackwireSpinal")

fork := true
// forward standard input of the sbt process to the forked process
run / connectInput := true

traceLevel in run := 0
traceLevel := 0