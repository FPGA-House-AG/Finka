val spinalVersion = "1.7.3a"
val spinalDir = "../SpinalHDL.upstream"
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

lazy val vexRiscv = RootProject(file("../VexRiscv.pinned")) // 87c8822f55c0674d24a55b2d83255b60f8a6146e
lazy val spinalCorundum = RootProject(file("../SpinalCorundum")) // 29e0ab65c5a077b1a4c1ee47f40c31b526537eda

lazy val spinalHdlCore = ProjectRef(file(spinalDir), "core") // 88579afcef46effb1597177e6f7dd5ca860b0aef
lazy val spinalHdlLib = ProjectRef(file(spinalDir), "lib")
lazy val spinalHdlIdslPlugin = ProjectRef(file(spinalDir), "idslplugin")
lazy val spinalHdlSim = ProjectRef(file(spinalDir), "sim")

fork := true
// forward standard input of the sbt process to the forked process
run / connectInput := true
