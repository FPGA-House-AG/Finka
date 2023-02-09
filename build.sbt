val spinalVersion = "dev"
val spinalDir = "../SpinalHDL.dev" // 4ec5b9db71767e1776091afca6c401903a4ad747
//val spinalVersion = "dev"
//val spinalDir = "../SpinalHDL.dev"

lazy val finka = (project in file("."))
  .settings(
    inThisBuild(
      List(
        organization := "com.github.spinalhdl",
        scalaVersion := "2.11.12",
        version      := "1.0.0"
      )
    ),
    // two lines need to use IDSL plug-in from local ../SpinalHDL/
    scalacOptions += s"-Xplugin:${new File(baseDirectory.value + s"/" + spinalDir + s"/idslplugin/target/scala-2.11/spinalhdl-idsl-plugin_2.11-$spinalVersion.jar")}",
    scalacOptions += s"-Xplugin-require:idsl-plugin",
    libraryDependencies ++= Seq(
      // three lines replaced by dependsOn(spinal*, ...) below
      //"com.github.spinalhdl" % "spinalhdl-core_2.11" % spinalVersion,
      //"com.github.spinalhdl" % "spinalhdl-lib_2.11" % spinalVersion,
      //compilerPlugin("com.github.spinalhdl" % "spinalhdl-idsl-plugin_2.11" % spinalVersion),
      "org.scalatest" %% "scalatest" % "3.2.5",
      "org.yaml" % "snakeyaml" % "1.8"
    ),
    name := "Finka",
    Compile / scalaSource := baseDirectory.value / "hardware" / "scala"
    //Test / scalaSource := baseDirectory.value / "test" / "scala"
  )
  .dependsOn(vexRiscv)
  .dependsOn(spinalCorundum)
  .dependsOn(spinalHdlIdslPlugin, spinalHdlSim, spinalHdlCore, spinalHdlLib)

lazy val vexRiscv = RootProject(file("../VexRiscv.pinned")) // 2bc6e70f03edcacd875a4ec93714dc023ae136d3
lazy val spinalCorundum = RootProject(file("../SpinalCorundum")) // e033b3d56f620577ac5376d00d68232970389b3d

// git clone --branch dev git@github.com:likewise/SpinalHDL.git && cd SpinalHDL && git checkout 419c87b0285c77c823973badc5451db0ef0791b6
lazy val spinalHdlCore = ProjectRef(file(spinalDir), "core") 
lazy val spinalHdlLib = ProjectRef(file(spinalDir), "lib")
lazy val spinalHdlIdslPlugin = ProjectRef(file(spinalDir), "idslplugin")
lazy val spinalHdlSim = ProjectRef(file(spinalDir), "sim")

fork := true
// forward standard input of the sbt process to the forked process
run / connectInput := true

traceLevel in run := 0
traceLevel := 0