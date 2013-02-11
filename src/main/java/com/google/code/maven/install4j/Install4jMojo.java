/**
 * Copyright (C) 2009 Ridgetop Group, Inc.
 * 
 * This file is part of the maven-install4j project, hosted at http://code.google.com/p/maven-install4j/
 * 
 * maven-install4j is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * maven-install4j is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public
 * License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with maven-install4j. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.google.code.maven.install4j;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Properties;
import java.util.Map.Entry;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;
import org.codehaus.plexus.util.cli.StreamConsumer;

/**
 * <p>
 * Calls <code>install4jc</code>, the install4j Command Line Compiler.
 * </p>
 * <p>
 * The Compiler's options are documented at <a
 * href="http://resources.ej-technologies.com/install4j/help/doc/cli/options.html"
 * >http://resources.ej-technologies.com/install4j/help/doc/cli/options.html</a>. Only a subset of those
 * options are supported by the {@link Install4jMojo}.
 * </p>
 * 
 * @goal compile
 */
public class Install4jMojo extends AbstractMojo
{
	private static final int RESULT_CODE_SUCCESS = 0;

	/**
	 * The compiler executable. Can be a full path or the name of the executable. In the latter case, the
	 * executable must be in the PATH for the execution to work.
	 * 
	 * @parameter expression="${install4j.executable}"
	 * @required
	 */
	private String executable;

	/**
	 * The <code>*.install4j</code> configuration file to use.
	 * 
	 * @parameter expression="${install4j.configFile}"
	 * @required
	 */
	private String configFile;

	/**
	 * If set, will override the application version specified in the <code>.install4j</code> file.
	 * 
	 * @parameter expression="${install4j.releaseId}"
	 */
	private String releaseId;

	/**
	 * If set, will override any compiler variables with the values contained in this {@link Properties}
	 * instance.
	 * 
	 * @parameter
	 */
	private Properties compilerVariables;

	/**
	 * Makes install4j more "talkative".
	 * 
	 * @parameter expression="${install4j.verbose}" default-value="false"
	 */
	private boolean verbose;

	/**
	 * If <code>true</code>, <code>install4jc</code> will only perform a test run.
	 * 
	 * @parameter expression="${install4j.testOnly}" default-value="false"
	 */
	private boolean testOnly;

	/**
	 * <p>
	 * If <code>true</code>, <code>install4jc</code> will generate additional debug installers.
	 * </p>
	 * <p>
	 * Please note, that because these installers are generated in sub-directories, they will not be attached
	 * to the project and installed/deployed with it-- regardless of the {@link #attach} setting.
	 * </p>
	 * 
	 * @parameter expression="${install4j.debug}" default-value="false"
	 */
	private boolean debug;

	/**
	 * The output directory to place the install4j installers into.
	 * 
	 * @parameter expression="${install4j.outputDirectory}"
	 *            default-value="${project.build.directory}/install4j"
	 * @required
	 */
	private File outputDirectory;

	/**
	 * If <code>true</code>, the plugin will not run unless the <code>install4jc</code> executable specified
	 * in <code>executable</code> exists. Please note that this will likely not work if the
	 * <code>install4jc</code> executable is in the path and is only specified as a filename.
	 * 
	 * @parameter expression="${install4j.skipOnMissingExecutable}" default-value="false"
	 */
	private boolean skipOnMissingExecutable;

	/**
	 * Controls whether the plugin tries to attach the resulting installer executables to the project.
	 * 
	 * @parameter expression="${attach}" default-value="true"
	 */
	private boolean attach;

	/**
	 * @parameter default-value="${project}"
	 * @required
	 * @readonly
	 */
	private MavenProject project;

	/**
	 * The {@link MavenProjectHelper} to use.
	 * 
	 * @component
	 */
	private MavenProjectHelper projectHelper;

	/**
	 * @see org.apache.maven.plugin.AbstractMojo#execute()
	 */
	public void execute() throws MojoExecutionException
	{
		String compilerExecutablePath = getCompilerExecutablePath();
		if (this.skipOnMissingExecutable && !verifyExecutableExists(compilerExecutablePath))
		{
			getLog().info("Skipping install4j compilation.  Unable to find compiler: " + this.executable);
			return;
		}

		Commandline cli = new Commandline();
		cli.getShell().setQuotedArgumentsEnabled(false);
		cli.setExecutable(getCompilerExecutablePath());
		cli.addArguments(buildCompilerArguments());

		runCompilerExecutable(cli);

		if (this.attach)
			attachInstallers(findGeneratedInstallers());
	}

	/**
	 * Attaches the {@link File}s found by {@link #findGeneratedInstallers()} as additional artifacts for this
	 * project. The value of {@link File#getName()} will be used as the artifact's classifier.
	 * 
	 * @param installerFiles
	 *            the array of {@link File}s to be attached
	 */
	private void attachInstallers(File[] installerFiles)
	{
		for (int i = 0; i < installerFiles.length; i++)
		{
			File installerFile = installerFiles[i];
			String extension = FileUtils.extension(installerFile.getName());
			String name = FileUtils.removeExtension(FileUtils.filename(installerFile.getName()));
			String type = extension;
			String classifier = name;
			projectHelper.attachArtifact(project, type, classifier, installerFile);
		}
	}

	/**
	 * Runs the specified {@link Commandline}.
	 * 
	 * @param cli
	 *            the {@link Commandline} to be run
	 * @return the {@link Commandline}'s resultant <code>int</code> result code
	 * @throws MojoExecutionException
	 *             if any {@link CommandLineException}s are encountered or the {@link Commandline}'s result
	 *             code is not {@link #RESULT_CODE_SUCCESS}
	 */
	private void runCompilerExecutable(Commandline cli) throws MojoExecutionException
	{
		StreamLogger stdoutLogger = new StreamLogger(getLog());
		StreamLogger stderrLogger = new StreamLogger(getLog());

		int execResultCode = -1;
		try
		{
			getLog().info("Running the following command for install4j compile: " + cli.toString());
			execResultCode = CommandLineUtils.executeCommandLine(cli, stdoutLogger, stderrLogger);
		}
		catch (CommandLineException e)
		{
			throw new MojoExecutionException("Execution of install4jc failed.", e);
		}

		if (execResultCode != RESULT_CODE_SUCCESS)
			throw new MojoExecutionException("Execution of install4jc failed with a result code of: "
					+ execResultCode + ".");
	}

	/**
	 * Returns the absolute path to the <code>install4jc</code> executable.
	 * 
	 * @return the absolute path to the <code>install4jc</code> executable
	 */
	private String getCompilerExecutablePath()
	{
		File compilerFile = new File(this.executable);
		if (compilerFile.exists())
		{
			getLog().debug("Compiler executable was found at: " + executable);
			return compilerFile.getAbsolutePath();
		}

		// If we couldn't find the File above, we assume it's in the path
		return executable;
	}

	/**
	 * Returns <code>true</code> if the {@link File} at the specified path exists and is executable.
	 * 
	 * @param compilerPath
	 *            the path of the executable {@link File} to verify
	 * @return <code>true</code> if the {@link File} at the specified path exists and is executable
	 */
	private static boolean verifyExecutableExists(String compilerPath)
	{
		File executableFile = new File(compilerPath);
		if (executableFile.exists() && executableFile.canExecute())
		{
			return true;
		}
		else
		{
			return false;
		}
	}

	/**
	 * Builds an array of {@link String}s that contains the arguments to pass to the <code>install4jc</code>
	 * executable.
	 * 
	 * @return an array of {@link String}s that contains the arguments to pass to the <code>install4jc</code>
	 *         executable
	 */
	private String[] buildCompilerArguments()
	{
		ArrayList arguments = new ArrayList();

		if (verbose)
			arguments.add("--verbose");
		if (testOnly)
			arguments.add("--test");
		if (debug)
			arguments.add("--debug");
		if (releaseId != null && releaseId.trim().length() > 0)
			arguments.add("--release=" + releaseId.trim());
		if (outputDirectory != null)
			arguments.add("--destination=\"" + outputDirectory.getAbsolutePath() + "\"");
		if (compilerVariables != null && compilerVariables.size() > 0)
			arguments.add(convertCompilerVariables());

		arguments.add(configFile);

		String[] argsArray = new String[arguments.size()];
		arguments.toArray(argsArray);
		return argsArray;
	}

	/**
	 * Converts {@link #compilerVariables} to a {@link String} parameter that can be passed to
	 * <code>install4jc</code> on the command line.
	 * 
	 * @return a {@link String} parameter representation of {@link #compilerVariables} that can be passed to
	 *         <code>install4jc</code> on the command line
	 */
	private String convertCompilerVariables()
	{
		StringBuilder sb = new StringBuilder();
		sb.append("-D ");
		Iterator varIter = compilerVariables.entrySet().iterator();
		while (varIter.hasNext())
		{
			Entry varEntry = (Entry) varIter.next();
			String key = (String) varEntry.getKey();
			String value = (String) varEntry.getValue();

			sb.append(key);
			sb.append('=');
			sb.append(value);

			if (varIter.hasNext())
				sb.append(',');
		}

		return sb.toString();
	}

	/**
	 * Returns an array of {@link File}s for each of the generated install4j installers that can be found in
	 * {@link #outputDirectory}. Note that this is a fairly "dumb" search: it assumes any executable files in
	 * {@link #outputDirectory} are install4j installers.
	 * 
	 * @return an array of {@link File}s for each of the generated install4j installers that can be found in
	 *         {@link #outputDirectory}
	 */
	private File[] findGeneratedInstallers()
	{
		FileFilter installerFilter = new InstallerFileFilter();
		return outputDirectory.listFiles(installerFilter);
	}

	/**
	 * This {@link StreamConsumer} will redirect all output to the specified {@link Log}'s
	 * {@link Log#info(CharSequence)} method.
	 */
	private static class StreamLogger implements StreamConsumer
	{
		private final Log outputLog;

		/**
		 * Constructor.
		 * 
		 * @param outputLog
		 *            the {@link Log} to redirect the stream's output to
		 */
		public StreamLogger(Log outputLog)
		{
			this.outputLog = outputLog;
		}

		/**
		 * @see org.codehaus.plexus.util.cli.StreamConsumer#consumeLine(java.lang.String)
		 */
		public void consumeLine(String line)
		{
			this.outputLog.info(line);
		}
	}

	/**
	 * A file filter that only accepts generated install4j installers. Note that this is a fairly "dumb"
	 * filter: it assumes any executable files in are install4j installers.
	 */
	private static class InstallerFileFilter implements FileFilter
	{
		/**
		 * @see java.io.FileFilter#accept(java.io.File)
		 */
		public boolean accept(File pathname)
		{
			return pathname.canExecute();
		}
	}
}
