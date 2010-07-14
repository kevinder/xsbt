/* sbt -- Simple Build Tool
 * Copyright 2010 Mark Harrah
 */
package sbt

import inc._

	import java.io.File
	import compile.{AnalyzingCompiler, CompilerArguments, JavaCompiler}
	import classpath.ClasspathUtilities
	import classfile.Analyze
	import xsbti.api.Source
	import xsbti.AnalysisCallback
	import CompileSetup._
	import sbinary.DefaultProtocol.{ immutableMapFormat, immutableSetFormat, StringFormat }

final class CompileConfiguration(val sources: Seq[File], val classpath: Seq[File], val javaSrcBases: Seq[File],
	val previousAnalysis: Analysis, val previousSetup: Option[CompileSetup], val currentSetup: CompileSetup, val getAnalysis: File => Option[Analysis],
	val maxErrors: Int, val compiler: AnalyzingCompiler, val javac: JavaCompiler)

class AggressiveCompile(cacheDirectory: File)
{
	def apply(compiler: AnalyzingCompiler, javac: JavaCompiler, sources: Seq[File], classpath: Seq[File], outputDirectory: File, javaSrcBases: Seq[File] = Nil, options: Seq[String] = Nil, javacOptions: Seq[String] = Nil, maxErrors: Int = 100)(implicit log: Logger): Analysis =
	{
		val setup = new CompileSetup(outputDirectory, new CompileOptions(options, javacOptions), compiler.scalaInstance.actualVersion, CompileOrder.Mixed)
		compile1(sources, classpath, javaSrcBases, setup, store, Map.empty, compiler, javac, maxErrors)
	}

	def withBootclasspath(args: CompilerArguments, classpath: Seq[File]): Seq[File] =
		args.bootClasspath ++ classpath

	def compile1(sources: Seq[File], classpath: Seq[File], javaSrcBases: Seq[File], setup: CompileSetup, store: AnalysisStore, analysis: Map[File, Analysis], compiler: AnalyzingCompiler, javac: JavaCompiler, maxErrors: Int)(implicit log: Logger): Analysis =
	{
		val (previousAnalysis, previousSetup) = extract(store.get())
		val config = new CompileConfiguration(sources, classpath, javaSrcBases, previousAnalysis, previousSetup, setup, analysis.get _, maxErrors, compiler, javac)
		val result = compile2(config)
		store.set(result, setup)
		result
	}
	def compile2(config: CompileConfiguration)(implicit log: Logger, equiv: Equiv[CompileSetup]): Analysis =
	{
		import config._
		import currentSetup._
		val getAPI = (f: File) => {
			val extApis = getAnalysis(f) match { case Some(a) => a.apis.external; case None => Map.empty[String, Source] }
			extApis.get _
		}
		val apiOrEmpty = (api: Either[Boolean, Source]) => api.right.toOption.getOrElse( APIs.emptyAPI )
		val cArgs = new CompilerArguments(compiler.scalaInstance, compiler.cp)
		val externalAPI = apiOrEmpty compose Locate.value(withBootclasspath(cArgs, classpath), getAPI)
		
		val compile0 = (include: Set[File], callback: AnalysisCallback) => {
			IO.createDirectory(outputDirectory)
			val incSrc = sources.filter(include)
			val arguments = cArgs(incSrc, classpath, outputDirectory, options.options)
			compiler.compile(arguments, callback, maxErrors, log)
			val javaSrcs = incSrc.filter(javaOnly) 
			if(!javaSrcs.isEmpty)
			{
				import Path._
				val loader = ClasspathUtilities.toLoader(classpath, compiler.scalaInstance.loader)
				// TODO: Analyze needs to generate API from Java class files
				Analyze(outputDirectory, javaSrcs, javaSrcBases, log)(callback, loader) {
					javac(javaSrcs, classpath, outputDirectory, options.javacOptions)
				}
			}
		}
		
		val sourcesSet = sources.toSet
		val analysis = previousSetup match {
			case Some(previous) if equiv.equiv(previous, currentSetup) => previousAnalysis
			case _ => Incremental.prune(sourcesSet, previousAnalysis)
		}
		IncrementalCompile(sourcesSet, compile0, analysis, externalAPI)
	}
	private def extract(previous: Option[(Analysis, CompileSetup)]): (Analysis, Option[CompileSetup]) =
		previous match
		{
			case Some((an, setup)) => (an, Some(setup))
			case None => (Analysis.Empty, None)
		}
	def javaOnly(f: File) = f.getName.endsWith(".java")

	import AnalysisFormats._
	// The following intermediate definitions are needed because of Scala's implicit parameter rules.
	//   implicit def a(implicit b: Format[T[Int]]): Format[S] = ...
	// triggers a diverging expansion because Format[T[Int]] dominates Format[S]
	implicit val r = relationFormat[File,File]
	implicit val rF = relationsFormat(r,r,r, relationFormat[File, String])
	implicit val aF = analysisFormat(stampsFormat, apisFormat, rF)

	val store = AnalysisStore.sync(AnalysisStore.cached(FileBasedStore(cacheDirectory)(aF, setupFormat)))
}