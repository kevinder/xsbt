/* sbt -- Simple Build Tool
 * Copyright 2008, 2009 Mark Harrah
 */
package xsbt

import Artifact.{defaultExtension, defaultType}

import java.io.File

import org.apache.ivy.{core, plugins, util, Ivy}
import core.cache.DefaultRepositoryCacheManager
import core.module.descriptor.{DefaultArtifact, DefaultDependencyArtifactDescriptor, MDArtifact}
import core.module.descriptor.{DefaultDependencyDescriptor, DefaultModuleDescriptor,  ModuleDescriptor}
import core.module.id.{ArtifactId,ModuleId, ModuleRevisionId}
import core.settings.IvySettings
import plugins.matcher.PatternMatcher
import plugins.parser.m2.PomModuleDescriptorParser
import plugins.resolver.ChainResolver
import util.Message

final class IvySbt(configuration: IvyConfiguration)
{
	import configuration._
	/** ========== Configuration/Setup ============
	* This part configures the Ivy instance by first creating the logger interface to ivy, then IvySettings, and then the Ivy instance.
	* These are lazy so that they are loaded within the right context.  This is important so that no Ivy XML configuration needs to be loaded,
	* saving some time.  This is necessary because Ivy has global state (IvyContext, Message, DocumentBuilder, ...).
	*/
	private lazy val logger = new IvyLoggerInterface(log)
	private def withDefaultLogger[T](f: => T): T =
		IvySbt.synchronized // Ivy is not thread-safe.  In particular, it uses a static DocumentBuilder, which is not thread-safe
		{
			val originalLogger = Message.getDefaultLogger
			Message.setDefaultLogger(logger)
			try { f }
			finally { Message.setDefaultLogger(originalLogger) }
		}
	private lazy val settings =
	{
		val is = new IvySettings
		is.setBaseDir(paths.baseDirectory)
		IvySbt.configureCache(is, paths.cacheDirectory)
		if(resolvers.isEmpty)
			autodetectConfiguration(is)
		else
			IvySbt.setResolvers(is, resolvers, log)
		is
	}
	private lazy val ivy =
	{
		val i = Ivy.newInstance(settings)
		i.getLoggerEngine.pushLogger(logger)
		i
	}
	/** Called to configure Ivy when inline resolvers are not specified.
	* This will configure Ivy with an 'ivy-settings.xml' file if there is one or else use default resolvers.*/
	private def autodetectConfiguration(settings: IvySettings)
	{
		log.debug("Autodetecting configuration.")
		val defaultIvyConfigFile = IvySbt.defaultIvyConfiguration(paths.baseDirectory)
		if(defaultIvyConfigFile.canRead)
			settings.load(defaultIvyConfigFile)
		else
			IvySbt.setResolvers(settings, Resolver.withDefaultResolvers(Nil), log)
	}
	/** ========== End Configuration/Setup ============*/

	/** Uses the configured Ivy instance within a safe context.*/
	def withIvy[T](f: Ivy => T): T =
		withDefaultLogger
		{
			ivy.pushContext()
			try { f(ivy) }
			finally { ivy.popContext() }
		}

	final class Module(val moduleConfiguration: ModuleConfiguration) extends NotNull
	{
		def logger = configuration.log
		def withModule[T](f: (Ivy,DefaultModuleDescriptor,String) => T): T =
			withIvy[T] { ivy => f(ivy, moduleDescriptor, defaultConfig) }

		import moduleConfiguration._
		private lazy val (moduleDescriptor: DefaultModuleDescriptor, defaultConfig: String) =
		{
			val (baseModule, baseConfiguration) =
				if(isUnconfigured)
					autodetectDependencies(IvySbt.toID(module))
				else
					configureModule
			ivyScala.foreach(IvyScala.checkModule(baseModule, baseConfiguration))
			baseModule.getExtraAttributesNamespaces.asInstanceOf[java.util.Map[String,String]].put("m", "m")
			(baseModule, baseConfiguration)
		}
		private def configureModule =
		{
			val moduleID = newConfiguredModuleID
			val defaultConf = defaultConfiguration getOrElse Configurations.config(ModuleDescriptor.DEFAULT_CONFIGURATION)
			log.debug("Using inline dependencies specified in Scala" + (if(ivyXML.isEmpty) "." else " and XML."))

			val parser = IvySbt.parseIvyXML(ivy.getSettings, IvySbt.wrapped(module, ivyXML), moduleID, defaultConf.name, validate)

			IvySbt.addArtifacts(moduleID, artifacts)
			IvySbt.addDependencies(moduleID, dependencies, parser)
			IvySbt.addMainArtifact(moduleID)
			(moduleID, parser.getDefaultConf)
		}
		private def newConfiguredModuleID =
		{
			val mod = new DefaultModuleDescriptor(IvySbt.toID(module), "release", null, false)
			mod.setLastModified(System.currentTimeMillis)
			configurations.foreach(config => mod.addConfiguration(IvySbt.toIvyConfiguration(config)))
			mod
		}

		/** Parses the given Maven pom 'pomFile'.*/
		private def readPom(pomFile: File) =
		{
			val md = PomModuleDescriptorParser.getInstance.parseDescriptor(settings, toURL(pomFile), validate)
			(IvySbt.toDefaultModuleDescriptor(md), "compile")
		}
		/** Parses the given Ivy file 'ivyFile'.*/
		private def readIvyFile(ivyFile: File) =
		{
			val url = toURL(ivyFile)
			val parser = new CustomXmlParser.CustomParser(settings)
			parser.setValidate(validate)
			parser.setSource(url)
			parser.parse()
			val md = parser.getModuleDescriptor()
			(IvySbt.toDefaultModuleDescriptor(md), parser.getDefaultConf)
		}
		private def toURL(file: File) = file.toURI.toURL
		/** Called to determine dependencies when the dependency manager is SbtManager and no inline dependencies (Scala or XML)
		* are defined.  It will try to read from pom.xml first and then ivy.xml if pom.xml is not found.  If neither is found,
		* Ivy is configured with defaults.*/
		private def autodetectDependencies(module: ModuleRevisionId) =
		{
			log.debug("Autodetecting dependencies.")
			val defaultPOMFile = IvySbt.defaultPOM(paths.baseDirectory)
			if(defaultPOMFile.canRead)
				readPom(defaultPOMFile)
			else
			{
				val defaultIvy = IvySbt.defaultIvyFile(paths.baseDirectory)
				if(defaultIvy.canRead)
					readIvyFile(defaultIvy)
				else
				{
					val defaultConf = ModuleDescriptor.DEFAULT_CONFIGURATION
					log.warn("No dependency configuration found, using defaults.")
					val moduleID = DefaultModuleDescriptor.newDefaultInstance(module)
					IvySbt.addMainArtifact(moduleID)
					IvySbt.addDefaultArtifact(defaultConf, moduleID)
					(moduleID, defaultConf)
				}
			}
		}
	}
}

private object IvySbt
{
	val DefaultIvyConfigFilename = "ivysettings.xml"
	val DefaultIvyFilename = "ivy.xml"
	val DefaultMavenFilename = "pom.xml"

	private def defaultIvyFile(project: File) = new File(project, DefaultIvyFilename)
	private def defaultIvyConfiguration(project: File) = new File(project, DefaultIvyConfigFilename)
	private def defaultPOM(project: File) = new File(project, DefaultMavenFilename)

	/** Sets the resolvers for 'settings' to 'resolvers'.  This is done by creating a new chain and making it the default. */
	private def setResolvers(settings: IvySettings, resolvers: Seq[Resolver], log: IvyLogger)
	{
		val newDefault = new ChainResolver
		newDefault.setName("sbt-chain")
		newDefault.setReturnFirst(true)
		newDefault.setCheckmodified(true)
		resolvers.foreach(r => newDefault.add(ConvertResolver(r)))
		settings.addResolver(newDefault)
		settings.setDefaultResolver(newDefault.getName)
		log.debug("Using repositories:\n" + resolvers.mkString("\n\t"))
	}
	private def configureCache(settings: IvySettings, dir: Option[File])
	{
		val cacheDir = dir.getOrElse(settings.getDefaultRepositoryCacheBasedir())
		val manager = new DefaultRepositoryCacheManager("default-cache", settings, cacheDir)
		manager.setUseOrigin(true)
		manager.setChangingMatcher(PatternMatcher.REGEXP);
		manager.setChangingPattern(".*-SNAPSHOT");
		settings.setDefaultRepositoryCacheManager(manager)
	}
	private def toIvyConfiguration(configuration: Configuration) =
	{
		import org.apache.ivy.core.module.descriptor.{Configuration => IvyConfig}
		import IvyConfig.Visibility._
		import configuration._
		new IvyConfig(name, if(isPublic) PUBLIC else PRIVATE, description, extendsConfigs.map(_.name).toArray, transitive, null)
	}
	private def addDefaultArtifact(defaultConf: String, moduleID: DefaultModuleDescriptor) =
		moduleID.addArtifact(defaultConf, new MDArtifact(moduleID, moduleID.getModuleRevisionId.getName, defaultType, defaultExtension))
	/** Adds the ivy.xml main artifact. */
	private def addMainArtifact(moduleID: DefaultModuleDescriptor)
	{
		val artifact = DefaultArtifact.newIvyArtifact(moduleID.getResolvedModuleRevisionId, moduleID.getPublicationDate)
		moduleID.setModuleArtifact(artifact)
		moduleID.check()
	}
	/** Converts the given sbt module id into an Ivy ModuleRevisionId.*/
	private[xsbt] def toID(m: ModuleID) =
	{
		import m._
		ModuleRevisionId.newInstance(organization, name, revision)
	}
	private def toIvyArtifact(moduleID: ModuleDescriptor, a: Artifact, configurations: Iterable[String]): MDArtifact =
	{
		val artifact = new MDArtifact(moduleID, a.name, a.`type`, a.extension, null, extra(a))
		configurations.foreach(artifact.addConfiguration)
		artifact
	}
	private def extra(artifact: Artifact) = artifact.classifier.map(c => javaMap("m:classifier" -> c)).getOrElse(null)

	private object javaMap
	{
		import java.util.{HashMap, Map}
		def apply[K,V](pairs: (K,V)*): Map[K,V] =
		{
			val map = new HashMap[K,V]
			pairs.foreach { case (key, value) => map.put(key, value) }
			map
		}
	}
	/** Creates a full ivy file for 'module' using the 'content' XML as the part after the &lt;info&gt;...&lt;/info&gt; section. */
	private def wrapped(module: ModuleID, content: scala.xml.NodeSeq) =
	{
		import module._
		<ivy-module version="2.0">
			<info organisation={organization} module={name} revision={revision}/>
			{content}
		</ivy-module>
	}
	/** Parses the given in-memory Ivy file 'xml', using the existing 'moduleID' and specifying the given 'defaultConfiguration'. */
	private def parseIvyXML(settings: IvySettings, xml: scala.xml.NodeSeq, moduleID: DefaultModuleDescriptor, defaultConfiguration: String, validate: Boolean): CustomXmlParser.CustomParser =
		parseIvyXML(settings,  xml.toString, moduleID, defaultConfiguration, validate)
	/** Parses the given in-memory Ivy file 'xml', using the existing 'moduleID' and specifying the given 'defaultConfiguration'. */
	private def parseIvyXML(settings: IvySettings, xml: String, moduleID: DefaultModuleDescriptor, defaultConfiguration: String, validate: Boolean): CustomXmlParser.CustomParser =
	{
		val parser = new CustomXmlParser.CustomParser(settings)
		parser.setMd(moduleID)
		parser.setDefaultConf(defaultConfiguration)
		parser.setValidate(validate)
		parser.setInput(xml.getBytes)
		parser.parse()
		parser
	}

	/** This method is used to add inline dependencies to the provided module. */
	def addDependencies(moduleID: DefaultModuleDescriptor, dependencies: Iterable[ModuleID], parser: CustomXmlParser.CustomParser)
	{
		for(dependency <- dependencies)
		{
			val dependencyDescriptor = new DefaultDependencyDescriptor(moduleID, toID(dependency), false, dependency.isChanging, dependency.isTransitive)
			dependency.configurations match
			{
				case None => // The configuration for this dependency was not explicitly specified, so use the default
					parser.parseDepsConfs(parser.getDefaultConf, dependencyDescriptor)
				case Some(confs) => // The configuration mapping (looks like: test->default) was specified for this dependency
					parser.parseDepsConfs(confs, dependencyDescriptor)
			}
			for(artifact <- dependency.explicitArtifacts)
			{
				import artifact.{name, classifier, `type`, extension, url}
				val extraMap = extra(artifact)
				val ivyArtifact = new DefaultDependencyArtifactDescriptor(dependencyDescriptor, name, `type`, extension, url.getOrElse(null), extraMap)
				for(conf <- dependencyDescriptor.getModuleConfigurations)
					dependencyDescriptor.addDependencyArtifact(conf, ivyArtifact)
			}
			moduleID.addDependency(dependencyDescriptor)
		}
	}
	/** This method is used to add inline artifacts to the provided module. */
	def addArtifacts(moduleID: DefaultModuleDescriptor, artifacts: Iterable[Artifact])
	{
		val allConfigurations = moduleID.getPublicConfigurationsNames
		for(artifact <- artifacts)
		{
			val configurationStrings =
			{
				val artifactConfigurations = artifact.configurations
				if(artifactConfigurations.isEmpty)
					allConfigurations
				else
					artifactConfigurations.map(_.name)
			}
			val ivyArtifact = toIvyArtifact(moduleID, artifact, configurationStrings)
			configurationStrings.foreach(configuration => moduleID.addArtifact(configuration, ivyArtifact))
		}
	}
	/** This code converts the given ModuleDescriptor to a DefaultModuleDescriptor by casting or generating an error.
	* Ivy 2.0.0 always produces a DefaultModuleDescriptor. */
	private def toDefaultModuleDescriptor(md: ModuleDescriptor) =
		md match
		{
			case dmd: DefaultModuleDescriptor => dmd
			case _ => error("Unknown ModuleDescriptor type.")
		}
}