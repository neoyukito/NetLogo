import sbt._
import Keys._
import Def.Initialize
import NetLogoBuild.{ marketingVersion, numericMarketingVersion }
import NetLogoPackaging.{ aggregateOnlyFiles, netLogoRoot, buildVariables, webTarget }
import java.nio.file.{ FileAlreadyExistsException, Files, StandardCopyOption }

object PackageAction {
  type JVMOptionFinder  = (PlatformBuild, SubApplication) => Seq[String]
  type JarAndDepFinder  = (PlatformBuild, SubApplication) => Def.Initialize[Task[(File, Seq[File])]]
  type BundledDirFinder = (PlatformBuild) => Seq[BundledDirectory]
  type MainClassFinder  = PartialFunction[(String, String), String]
  type AggregateBuild   = (File, File, BuildJDK, Map[SubApplication, File], Map[String, String], Seq[File]) => File

  private def buildSubApplication(
    appMainClass: MainClassFinder,
    jvmOptions: JVMOptionFinder,
    platform: PlatformBuild,
    bundledDirs: Seq[BundledDirectory],
    app: SubApplication,
    jdk: BuildJDK,
    version: String,
    variables: Map[String, String],
    buildDirectory: File,
    mainJar: File,
    dependencies: Seq[File],
    distDir: File,
    netLogoDir: File): File = {
      val artifactsDir    = buildDirectory / "out" / "artifacts"
      val outputDirectory = buildDirectory / "target"
      IO.delete(buildDirectory)
      IO.createDirectory(buildDirectory)

      val configurationVariables = variables +
      ("appName"        -> app.name) +
      ("buildDirectory" -> buildDirectory.getAbsolutePath)

      ConfigurationFiles.writeConfiguration(platform.shortName, app, distDir / "configuration", buildDirectory, configurationVariables)

      val copiedBundleFiles: Seq[(File, File)] =
        bundledDirs.flatMap(_.fileMappings.map(t => (t._1, new java.io.File(artifactsDir, t._2))))

      val jarMap = {
        val allJars = (mainJar +: dependencies)
        allJars zip allJars.map(f => artifactsDir / f.getName)
      }

      val additionalArtifacts = app.additionalArtifacts(distDir)
      val artifactCopies = additionalArtifacts zip additionalArtifacts.map(artifactsDir / _.getName)

      val allFileCopies: Seq[(File, File)] = jarMap ++ copiedBundleFiles ++ artifactCopies

      FileActions.copyAll(allFileCopies)

      val allFiles: Seq[File] = allFileCopies.map(_._2)

      JavaPackager(jdk, appMainClass(platform.shortName, app.name), platform.nativeFormat, platform.jvmOptions, app,
        srcDir = artifactsDir, srcFiles = allFiles, outDir = outputDirectory,
        buildDirectory = buildDirectory, mainJar = mainJar, appVersion = version, jvmOptions = jvmOptions(platform, app))
  }

  type SubAppFunc = ((PlatformBuild, SubApplication, BuildJDK)) => Def.Initialize[Task[File]]
  def subApplication(
    appMainClass:    MainClassFinder,
    jarAndDepFinder: JarAndDepFinder,
    bundledDirsInit: Def.Initialize[BundledDirFinder],
    jvmOptions:      JVMOptionFinder): Def.Initialize[Task[SubAppFunc]] =
      Def.task[SubAppFunc] {
      { (platform: PlatformBuild, app: SubApplication, buildJDK: BuildJDK) =>
        Def.bind(jarAndDepFinder(platform, app)) { jarTask =>
          Def.task {
            val cacheName = app.name + "-" + platform.shortName + "-" + buildJDK.arch
            val (mainJar, dependencies) = jarTask.value
            val inputFiles: Set[File] = Set(mainJar) ++ bundledDirsInit.value(platform).flatMap(_.files).toSet
            FileFunction.cached(streams.value.cacheDirectory / cacheName, inStyle = FilesInfo.exists, outStyle = FilesInfo.exists) {
              (in: Set[File]) =>
                val distDir         = baseDirectory.value
                val netLogoDir      = netLogoRoot.value
                val buildDirectory  = target.value / app.name / (platform.shortName + "-" + buildJDK.arch)
                val variables       = buildVariables.value
                Set(buildSubApplication(
                  appMainClass, jvmOptions,
                  platform, bundledDirsInit.value(platform), app, buildJDK,
                  numericMarketingVersion.value, variables, buildDirectory, mainJar,
                  dependencies, distDir, netLogoDir))
            }(inputFiles).head
          }
        }
      }.tupled
      }


    def aggregate(
      platformName:      String,
      aggregatePackager: PackageAction.AggregateBuild,
      packageApp:        Initialize[InputTask[File]],
      aggregateKey:      Scoped)
    (jdk:               BuildJDK = PathSpecifiedJDK): Def.Initialize[Task[File]] = {

      val subApps = Seq(NetLogoCoreApp, NetLogoThreeDApp, NetLogoLoggingApp, HubNetClientApp)
      val app = subApps.head

      type AppPair = (SubApplication, File)

      val versionTag =
        if (jdk == PathSpecifiedJDK)
          ""
        else
          s" ${jdk.version}-${jdk.arch}"

      def appTuple(app: SubApplication): Def.Initialize[Task[AppPair]] =
        Def.map(packageApp.toTask(s" $platformName ${app.name}$versionTag"))(_.map(f => (app -> f)))

      val appMap: Def.Initialize[Task[Map[SubApplication, File]]] =
        new Scoped.RichTaskSeq(subApps.map(appTuple)).join.map(_.toMap)

      def downloadPath(initialLocation: File, downloadDir: File, version: String): File = {
        val extension = initialLocation.getName.split('.').last
        val newName =
          if (jdk == PathSpecifiedJDK)
            s"NetLogo-${version}.${extension}"
          else
            s"NetLogo-${version}-${jdk.arch}.${extension}"
        downloadDir / newName
      }

      Def.task {
        val initialInstaller: File =
          aggregatePackager(target.value,
            baseDirectory.value / "configuration" / "aggregate" / platformName,
            jdk, appMap.value, buildVariables.value,
            (aggregateOnlyFiles in aggregateKey).value) // specialize on aggregateKey for platform-dependant files
        val dlPath = downloadPath(initialInstaller, webTarget.value, marketingVersion.value)
        IO.createDirectory(webTarget.value)
        IO.move(initialInstaller, dlPath)
        dlPath
      }
    }
}
