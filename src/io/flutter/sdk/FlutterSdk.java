/*
 * Copyright 2016 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.sdk;

import com.google.gson.*;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.externalSystem.service.execution.InvalidSdkException;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.impl.libraries.ProjectLibraryTable;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.*;
import com.intellij.util.ui.EdtInvocationManager;
import com.jetbrains.lang.dart.sdk.DartSdk;
import com.jetbrains.lang.dart.sdk.DartSdkUtil;
import io.flutter.FlutterUtils;
import io.flutter.bazel.Workspace;
import io.flutter.dart.DartPlugin;
import io.flutter.pub.PubRoot;
import io.flutter.run.FlutterDevice;
import io.flutter.run.FlutterLaunchMode;
import io.flutter.run.common.RunMode;
import io.flutter.samples.FlutterSample;
import io.flutter.samples.FlutterSampleManager;
import io.flutter.settings.FlutterSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;

import static java.util.Arrays.asList;

public class FlutterSdk {
  public static final String FLUTTER_SDK_GLOBAL_LIB_NAME = "Flutter SDK";

  public static final String DART_SDK_SUFFIX = "/bin/cache/dart-sdk";

  private static final String DART_CORE_SUFFIX = DART_SDK_SUFFIX + "/lib/core";

  private static final Logger LOG = Logger.getInstance(FlutterSdk.class);

  private static final Map<String, FlutterSdk> projectSdkCache = new HashMap<>();

  private final @NotNull VirtualFile myHome;
  private final @NotNull FlutterSdkVersion myVersion;
  private final Map<String, String> cachedConfigValues = new HashMap<>();

  // TODO(pq): make this an instance field as soon as SDKs are being cached and not constantly regenerated.
  private static FlutterSampleManager sampleManager;

  private FlutterSdk(@NotNull final VirtualFile home, @NotNull final FlutterSdkVersion version) {
    myHome = home;
    myVersion = version;
  }

  /**
   * Return the FlutterSdk for the given project.
   * <p>
   * Returns null if the Dart SDK is not set or does not exist.
   */
  @Nullable
  public static FlutterSdk getFlutterSdk(@NotNull final Project project) {
    if (project.isDisposed()) {
      return null;
    }
    final DartSdk dartSdk = DartPlugin.getDartSdk(project);
    if (dartSdk == null) {
      return null;
    }

    final String dartPath = dartSdk.getHomePath();
    if (!dartPath.endsWith(DART_SDK_SUFFIX)) {
      return null;
    }

    final String sdkPath = dartPath.substring(0, dartPath.length() - DART_SDK_SUFFIX.length());

    // Cache based on the project and path ('e41cfa3d:/Users/devoncarew/projects/flutter/flutter').
    final String cacheKey = project.getLocationHash() + ":" + sdkPath;
    return projectSdkCache.computeIfAbsent(cacheKey, s -> forPath(sdkPath));
  }

  /**
   * Return the FlutterSdk for a project in a Bazel workspace.
   * <p>
   * Returns null if we are not in a bazel project.
   * <p>
   * NOTE that the Bazel FlutterSdk does not have the same features defined as the normal SDK.
   * Only use this if you are sure you know what you are doing.
   */
  public static FlutterSdk forBazel(@NotNull final Project project) {
    // If this is not a bazel project, return null.
    final Workspace workspace = Workspace.load(project);
    if (workspace == null) {
      return null;
    }
    return new BazelSdk(project, workspace);
  }

  /**
   * Return the FlutterSdk for a project, using a pub or bazel-based SDK as appropriate.
   * <p>
   * NOTE that the Bazel FlutterSdk does not have the same features defined as the normal SDK.
   * Only use this if you are sure you know what you are doing.
   */
  @Nullable
  public static FlutterSdk forPubOrBazel(@NotNull final Project project) {
    return FlutterSettings.getInstance().shouldUseBazel() ? FlutterSdk.forBazel(project) : FlutterSdk.getFlutterSdk(project);
  }

  /**
   * Returns the Flutter SDK for a project that has a possibly broken "Dart SDK" project library.
   * <p>
   * (This can happen for a newly-cloned Flutter SDK where the Dart SDK is not cached yet.)
   */
  @Nullable
  public static FlutterSdk getIncomplete(@NotNull final Project project) {
    if (project.isDisposed()) {
      return null;
    }
    final Library lib = getDartSdkLibrary(project);
    if (lib == null) {
      return null;
    }
    return getFlutterFromDartSdkLibrary(lib);
  }

  @Nullable
  public static FlutterSdk forPath(@NotNull final String path) {
    final VirtualFile home = LocalFileSystem.getInstance().findFileByPath(path);
    if (home == null || !FlutterSdkUtil.isFlutterSdkHome(path)) {
      return null;
    }
    else {
      return new FlutterSdk(home, FlutterSdkVersion.readFromSdk(home));
    }
  }

  @Nullable
  private static Library getDartSdkLibrary(@NotNull Project project) {
    final Library[] libraries = ProjectLibraryTable.getInstance(project).getLibraries();
    for (Library lib : libraries) {
      if ("Dart SDK".equals(lib.getName())) {
        return lib;
      }
    }
    return null;
  }

  @Nullable
  private static FlutterSdk getFlutterFromDartSdkLibrary(Library lib) {
    final String[] urls = lib.getUrls(OrderRootType.CLASSES);
    for (String url : urls) {
      if (url.endsWith(DART_CORE_SUFFIX)) {
        final String flutterUrl = url.substring(0, url.length() - DART_CORE_SUFFIX.length());
        final VirtualFile home = VirtualFileManager.getInstance().findFileByUrl(flutterUrl);
        return home == null ? null : new FlutterSdk(home, FlutterSdkVersion.readFromSdk(home));
      }
    }
    return null;
  }

  public FlutterCommand flutterVersion() {
    return new FlutterCommand(this, getHome(), FlutterCommand.Type.VERSION);
  }

  public FlutterCommand flutterUpgrade() {
    return new FlutterCommand(this, getHome(), FlutterCommand.Type.UPGRADE);
  }

  public FlutterCommand flutterClean(@NotNull PubRoot root) {
    return new FlutterCommand(this, root.getRoot(), FlutterCommand.Type.CLEAN);
  }

  public FlutterCommand flutterDoctor() {
    return new FlutterCommand(this, getHome(), FlutterCommand.Type.DOCTOR);
  }

  public FlutterCommand flutterCreate(@NotNull VirtualFile appDir, @Nullable FlutterCreateAdditionalSettings additionalSettings) {
    final List<String> args = new ArrayList<>();
    if (additionalSettings != null) {
      args.addAll(additionalSettings.getArgs());
    }

    // keep as the last argument
    args.add(appDir.getName());

    final String[] vargs = args.toArray(new String[0]);

    return new FlutterCommand(this, appDir.getParent(), FlutterCommand.Type.CREATE, vargs);
  }

  public FlutterCommand flutterPackagesGet(@NotNull PubRoot root) {
    return new FlutterCommand(this, root.getRoot(), FlutterCommand.Type.PACKAGES_GET);
  }

  public FlutterCommand flutterPackagesUpgrade(@NotNull PubRoot root) {
    return new FlutterCommand(this, root.getRoot(), FlutterCommand.Type.PACKAGES_UPGRADE);
  }

  public FlutterCommand flutterPackagesPub(@Nullable PubRoot root, String... args) {
    return new FlutterCommand(this, root == null ? null : root.getRoot(), FlutterCommand.Type.PACKAGES_PUB, args);
  }

  public FlutterCommand flutterMakeHostAppEditable(@NotNull PubRoot root) {
    return new FlutterCommand(this, root.getRoot(), FlutterCommand.Type.MAKE_HOST_APP_EDITABLE);
  }

  public FlutterCommand flutterBuild(@NotNull PubRoot root, String... additionalArgs) {
    return new FlutterCommand(this, root.getRoot(), FlutterCommand.Type.BUILD, additionalArgs);
  }

  public FlutterCommand flutterConfig(String... additionalArgs) {
    return new FlutterCommand(this, getHome(), FlutterCommand.Type.CONFIG, additionalArgs);
  }

  public FlutterCommand flutterListSamples(@NotNull File indexFile) {
    return new FlutterCommand(this, getHome(), FlutterCommand.Type.LIST_SAMPLES, indexFile.getAbsolutePath());
  }

  public FlutterCommand flutterRun(@NotNull PubRoot root,
                                   @NotNull VirtualFile main,
                                   @Nullable FlutterDevice device,
                                   @NotNull RunMode mode,
                                   @NotNull FlutterLaunchMode flutterLaunchMode,
                                   @NotNull Project project,
                                   String... additionalArgs) {
    final List<String> args = new ArrayList<>();
    args.add("--machine");
    if (FlutterSettings.getInstance().isVerboseLogging()) {
      args.add("--verbose");
    }

    if (flutterLaunchMode == FlutterLaunchMode.DEBUG) {
      if (FlutterSettings.getInstance().isTrackWidgetCreationEnabled(project)) {
        args.add("--track-widget-creation");
      }
    }

    if (device != null) {
      args.add("--device-id=" + device.deviceId());
    }

    if (mode == RunMode.DEBUG) {
      args.add("--start-paused");
    }

    if (flutterLaunchMode == FlutterLaunchMode.PROFILE) {
      args.add("--profile");
    }
    else if (flutterLaunchMode == FlutterLaunchMode.RELEASE) {
      args.add("--release");
    }

    args.addAll(asList(additionalArgs));

    // Make the path to main relative (to make the command line prettier).
    final String mainPath = root.getRelativePath(main);
    if (mainPath == null) {
      throw new IllegalArgumentException("main isn't within the pub root: " + main.getPath());
    }
    args.add(FileUtil.toSystemDependentName(mainPath));

    return new FlutterCommand(this, root.getRoot(), FlutterCommand.Type.RUN, args.toArray(new String[]{ }));
  }

  public FlutterCommand flutterAttach(@NotNull PubRoot root, @NotNull VirtualFile main, @Nullable FlutterDevice device,
                                      @NotNull FlutterLaunchMode flutterLaunchMode, String... additionalArgs) {
    final List<String> args = new ArrayList<>();
    args.add("--machine");
    if (FlutterSettings.getInstance().isVerboseLogging()) {
      args.add("--verbose");
    }

    // TODO(messick): Check that 'flutter attach' supports these arguments.
    if (flutterLaunchMode == FlutterLaunchMode.PROFILE) {
      args.add("--profile");
    }
    else if (flutterLaunchMode == FlutterLaunchMode.RELEASE) {
      args.add("--release");
    }

    if (device != null) {
      args.add("--device-id=" + device.deviceId());
    }

    // TODO(messick): Add others (target, debug-port).
    args.addAll(asList(additionalArgs));

    // Make the path to main relative (to make the command line prettier).
    final String mainPath = root.getRelativePath(main);
    if (mainPath == null) {
      throw new IllegalArgumentException("main isn't within the pub root: " + main.getPath());
    }
    args.add(FileUtil.toSystemDependentName(mainPath));

    return new FlutterCommand(this, root.getRoot(), FlutterCommand.Type.ATTACH, args.toArray(new String[]{ }));
  }

  public FlutterCommand flutterRunWeb(@NotNull PubRoot root, @NotNull RunMode mode, String... additionalArgs) {
    // TODO(jwren): After debugging is supported by webdev, this should be modified to check for debug and add
    // any additional needed flags: i.e. if (mode == RunMode.DEBUG) { args.add("--debug"); }
    // See https://github.com/flutter/flutter-intellij/issues/3349.

    // flutter packages pub global run webdev daemon
    return new FlutterWebCommand(this, root.getRoot(), FlutterCommand.Type.FLUTTER_WEB_RUN, additionalArgs);
  }

  public FlutterCommand flutterRunOnTester(@NotNull PubRoot root, @NotNull String mainPath) {
    final List<String> args = new ArrayList<>();
    args.add("--machine");
    args.add("--device-id=flutter-tester");
    args.add(mainPath);
    return new FlutterCommand(this, root.getRoot(), FlutterCommand.Type.RUN, args.toArray(new String[]{ }));
  }

  public FlutterCommand flutterTest(@NotNull PubRoot root, @NotNull VirtualFile fileOrDir, @Nullable String testNameSubstring,
                                    @NotNull RunMode mode) {

    final List<String> args = new ArrayList<>();
    if (myVersion.flutterTestSupportsMachineMode()) {
      args.add("--machine");
      // Otherwise, just run it normally and show the output in a non-test console.
    }
    if (mode == RunMode.DEBUG) {
      if (!myVersion.flutterTestSupportsMachineMode()) {
        throw new IllegalStateException("Flutter SDK is too old to debug tests");
      }
      args.add("--start-paused");
    }
    if (FlutterSettings.getInstance().isVerboseLogging()) {
      args.add("--verbose");
    }
    if (testNameSubstring != null) {
      if (!myVersion.flutterTestSupportsFiltering()) {
        throw new IllegalStateException("Flutter SDK is too old to select tests by name");
      }
      args.add("--plain-name");
      args.add(testNameSubstring);
    }

    if (!root.getRoot().equals(fileOrDir)) {
      // Make the path to main relative (to make the command line prettier).
      final String mainPath = root.getRelativePath(fileOrDir);
      if (mainPath == null) {
        throw new IllegalArgumentException("main isn't within the pub root: " + fileOrDir.getPath());
      }
      args.add(FileUtil.toSystemDependentName(mainPath));
    }

    return new FlutterCommand(this, root.getRoot(), FlutterCommand.Type.TEST, args.toArray(new String[]{ }));
  }

  /**
   * Runs "flutter --version" and waits for it to complete.
   * <p>
   * This ensures that the Dart SDK exists and is up to date.
   * <p>
   * If project is not null, displays output in a console.
   *
   * @return true if successful (the Dart SDK exists).
   */
  public boolean sync(@NotNull Project project) {
    try {
      final OSProcessHandler handler = flutterVersion().startInConsole(project);
      if (handler == null) {
        return false;
      }
      final Process process = handler.getProcess();
      process.waitFor();
      if (process.exitValue() != 0) {
        return false;
      }
      final VirtualFile flutterBin = myHome.findChild("bin");
      if (flutterBin == null) {
        return false;
      }
      flutterBin.refresh(false, true);
      return flutterBin.findFileByRelativePath("cache/dart-sdk") != null;
    }
    catch (InterruptedException e) {
      FlutterUtils.warn(LOG, e);
      return false;
    }
  }

  /**
   * Runs flutter create and waits for it to finish.
   * <p>
   * Shows output in a console unless the module parameter is null.
   * <p>
   * Notifies process listener if one is specified.
   * <p>
   * Returns the PubRoot if successful.
   */
  @Nullable
  public PubRoot createFiles(@NotNull VirtualFile baseDir, @Nullable Module module, @Nullable ProcessListener listener,
                             @Nullable FlutterCreateAdditionalSettings additionalSettings) {
    final Process process;
    if (module == null) {
      process = flutterCreate(baseDir, additionalSettings).start(null, listener);
    }
    else {
      process = flutterCreate(baseDir, additionalSettings).startInModuleConsole(module, null, listener);
    }
    if (process == null) {
      return null;
    }

    try {
      if (process.waitFor() != 0) {
        return null;
      }
    }
    catch (InterruptedException e) {
      FlutterUtils.warn(LOG, e);
      return null;
    }

    if (EdtInvocationManager.getInstance().isEventDispatchThread()) {
      VfsUtil.markDirtyAndRefresh(false, true, true, baseDir); // Need this for AS.
    }
    else {
      baseDir.refresh(false, true); // The current thread must NOT be in a read action.
    }
    return PubRoot.forDirectory(baseDir);
  }


  public List<FlutterSample> getSamples() {
    if (sampleManager == null) {
      sampleManager = new FlutterSampleManager(this);
    }
    return sampleManager.getSamples();
  }

  public Process startMakeHostAppEditable(@NotNull PubRoot root, @NotNull Project project) {
    final Module module = root.getModule(project);
    if (module == null) return null;
    // Refresh afterwards to ensure new directory is recognized.
    return flutterMakeHostAppEditable(root).startInModuleConsole(module, root::refresh, null);
  }

  /**
   * Starts running 'flutter packages get' on the given pub root provided it's in one of this project's modules.
   * <p>
   * Shows output in the console associated with the given module.
   * <p>
   * Returns the process if successfully started.
   */
  public Process startPackagesGet(@NotNull PubRoot root, @NotNull Project project) {
    final Module module = root.getModule(project);
    if (module == null) return null;
    // Ensure pubspec is saved.
    FileDocumentManager.getInstance().saveAllDocuments();
    // Refresh afterwards to ensure Dart Plugin sees .packages and doesn't mistakenly nag to run pub.
    return flutterPackagesGet(root).startInModuleConsole(module, root::refresh, null);
  }

  /**
   * Starts running 'flutter packages upgrade' on the given pub root.
   * <p>
   * Shows output in the console associated with the given module.
   * <p>
   * Returns the process if successfully started.
   */
  public Process startPackagesUpgrade(@NotNull PubRoot root, @NotNull Project project) {
    final Module module = root.getModule(project);
    if (module == null) return null;
    // Ensure pubspec is saved.
    FileDocumentManager.getInstance().saveAllDocuments();
    return flutterPackagesUpgrade(root).startInModuleConsole(module, root::refresh, null);
  }

  @NotNull
  public VirtualFile getHome() {
    return myHome;
  }

  @NotNull
  public String getHomePath() {
    return myHome.getPath();
  }

  /**
   * Returns the Flutter Version as captured in the VERSION file. This version is very coarse grained and not meant for presentation and
   * rather only for sanity-checking the presence of baseline features (e.g, hot-reload).
   */
  @NotNull
  public FlutterSdkVersion getVersion() {
    return myVersion;
  }

  /**
   * Returns the path to the Dart SDK cached within the Flutter SDK, or null if it doesn't exist.
   */
  @Nullable
  public String getDartSdkPath() {
    return FlutterSdkUtil.pathToDartSdk(getHomePath());
  }

  /**
   * Query 'flutter config' for the given key, and optionally use any existing cached value.
   */
  @Nullable
  public String queryFlutterConfig(String key, boolean useCachedValue) {
    if (useCachedValue && cachedConfigValues.containsKey(key)) {
      return cachedConfigValues.get(key);
    }

    cachedConfigValues.put(key, queryFlutterConfigImpl(key));
    return cachedConfigValues.get(key);
  }

  private String queryFlutterConfigImpl(String key) {
    final FlutterCommand command = flutterConfig("--machine");
    final OSProcessHandler process = command.startProcess(false);

    if (process == null) {
      return null;
    }

    final StringBuilder stdout = new StringBuilder();
    process.addProcessListener(new ProcessAdapter() {
      boolean hasSeenStartingBrace = false;

      @Override
      public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
        // {"android-studio-dir":"/Applications/Android Studio 3.0 Preview.app/Contents"}
        if (outputType == ProcessOutputTypes.STDOUT) {
          // Ignore any non-json starting lines (like "Building flutter tool...").
          if (event.getText().startsWith("{")) {
            hasSeenStartingBrace = true;
          }
          if (hasSeenStartingBrace) {
            stdout.append(event.getText());
          }
        }
      }
    });

    LOG.info("Calling config --machine");
    final long start = System.currentTimeMillis();

    process.startNotify();

    if (process.waitFor(5000)) {
      final long duration = System.currentTimeMillis() - start;
      LOG.info("flutter config --machine: " + duration + "ms");

      final Integer code = process.getExitCode();
      if (code != null && code == 0) {
        try {
          final JsonParser jp = new JsonParser();
          final JsonElement elem = jp.parse(stdout.toString());
          if (elem.isJsonNull()) {
            FlutterUtils.warn(LOG, "Invalid Json from flutter config");
            return null;
          }

          final JsonObject obj = elem.getAsJsonObject();
          final JsonPrimitive primitive = obj.getAsJsonPrimitive(key);
          if (primitive != null) {
            return primitive.getAsString();
          }
        }
        catch (JsonSyntaxException ignored) {
        }
      }
      else {
        LOG.info("Exit code from flutter config --machine: " + code);
      }
    }
    else {
      LOG.info("Timeout when calling flutter config --machine");
    }

    return null;
  }

  /**
   * A {@link FlutterSdk} that is compatible with the Bazel version of the Dart and Flutter SDK.
   */
  public static class BazelSdk extends FlutterSdk {
    /**
     * The Bazel workspace for this project.
     */
    @NotNull final Workspace workspace;

    @NotNull final Project project;

    private BazelSdk(@NotNull Project project, @NotNull Workspace workspace) {
      super(
        Objects.requireNonNull(
          workspace.getRoot().findFileByRelativePath(
            Objects.requireNonNull(workspace.getSdkHome())
          )
        ),
        FlutterSdkVersion.readFromFile(
          Objects.requireNonNull(
            workspace.getRoot().findFileByRelativePath(
              Objects.requireNonNull(workspace.getVersionFile())
            )
          )
        )
      );
      this.workspace = workspace;
      this.project = project;
    }

    @Nullable
    @Override
    public String getDartSdkPath() {
      DartSdk sdk = DartSdk.getDartSdk(project);
      if (sdk == null) {
        return null;
      }
      return sdk.getHomePath();
    }

    @Override
    public FlutterCommand flutterPackagesPub(@Nullable PubRoot root, String... args) {
      return new BazelPubCommand(this, root == null ? null : root.getRoot(), FlutterCommand.Type.PACKAGES_PUB, args);
    }
  }

  /**
   * Creates a pub command that uses the bazel Dart SDK instead of the Flutter SDK.
   */
  private static class BazelPubCommand extends FlutterCommand {
    private BazelPubCommand(@NotNull BazelSdk sdk, @Nullable VirtualFile workDir, @NotNull Type type, String... args) {
      super(sdk, workDir, type, args);
    }

    @Override
    public String getDisplayCommand() {
      final List<String> words = new ArrayList<>();
      words.add("flutter");
      words.addAll(args);
      return String.join(" ", words);
    }

    @NotNull
    @Override
    public GeneralCommandLine createGeneralCommandLine(@Nullable Project project) {
      GeneralCommandLine original = super.createGeneralCommandLine(project);
      GeneralCommandLine line = new GeneralCommandLine();

      // Strip the subcommand from the parameters, because we will talk directly to pub.
      final List<String> parameters = new ArrayList<>(original.getParametersList().getList());
      final int pubSubcommandIndex = Collections.indexOfSubList(parameters, Type.PACKAGES_PUB.subCommand);
      for (int i = 0; i < Type.PACKAGES_PUB.subCommand.size(); i++) {
        parameters.remove(pubSubcommandIndex);
      }

      final String dartSdkPath = sdk.getDartSdkPath();
      if (dartSdkPath == null) {
        throw new InvalidSdkException("Unable to find the Dart SDK");
      }
      // Copy the original without the unneeded subcommand params and an adjusted sdk path.
      return line
        .withParameters(parameters)
        .withExePath(DartSdkUtil.getPubPath(dartSdkPath))
        .withCharset(CharsetToolkit.UTF8_CHARSET)
        .withEnvironment(original.getEnvironment()).withWorkDirectory(original.getWorkDirectory());
    }
  }
}
