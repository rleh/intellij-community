/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.codeInspection.ex;

import com.intellij.analysis.AnalysisScope;
import com.intellij.analysis.AnalysisUIOptions;
import com.intellij.analysis.PerformAnalysisInBackgroundOption;
import com.intellij.codeInsight.FileModificationService;
import com.intellij.codeInsight.daemon.ProblemHighlightFilter;
import com.intellij.codeInsight.daemon.impl.HighlightInfoProcessor;
import com.intellij.codeInsight.daemon.impl.LocalInspectionsPass;
import com.intellij.codeInspection.*;
import com.intellij.codeInspection.actions.CleanupInspectionIntention;
import com.intellij.codeInspection.lang.GlobalInspectionContextExtension;
import com.intellij.codeInspection.reference.RefElement;
import com.intellij.codeInspection.reference.RefEntity;
import com.intellij.codeInspection.reference.RefManagerImpl;
import com.intellij.codeInspection.reference.RefVisitor;
import com.intellij.codeInspection.ui.DefaultInspectionToolPresentation;
import com.intellij.codeInspection.ui.InspectionResultsView;
import com.intellij.codeInspection.ui.InspectionToolPresentation;
import com.intellij.codeInspection.ui.InspectionTreeState;
import com.intellij.concurrency.JobLauncher;
import com.intellij.concurrency.JobLauncherImpl;
import com.intellij.concurrency.SensitiveProgressWrapper;
import com.intellij.diagnostic.ThreadDumper;
import com.intellij.lang.annotation.ProblemGroup;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.notification.NotificationGroup;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.application.*;
import com.intellij.openapi.application.ex.ApplicationManagerEx;
import com.intellij.openapi.components.PathMacroManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.progress.impl.CoreProgressManager;
import com.intellij.openapi.progress.util.ProgressIndicatorUtils;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.project.ProjectUtilCore;
import com.intellij.openapi.roots.FileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.*;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.scope.packageSet.NamedScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.ui.GuiUtils;
import com.intellij.ui.content.*;
import com.intellij.util.ConcurrencyUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.Processor;
import com.intellij.util.TripleFunction;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.HashMap;
import com.intellij.util.containers.HashSet;
import com.intellij.util.ui.UIUtil;
import gnu.trove.THashSet;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.*;

public class GlobalInspectionContextImpl extends GlobalInspectionContextBase implements GlobalInspectionContext {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInspection.ex.GlobalInspectionContextImpl");
  private static final NotificationGroup NOTIFICATION_GROUP = NotificationGroup.toolWindowGroup("Inspection Results", ToolWindowId.INSPECTION);
  private final NotNullLazyValue<ContentManager> myContentManager;
  private volatile InspectionResultsView myView;
  private Content myContent;
  private volatile boolean myViewClosed = true;

  @NotNull
  private AnalysisUIOptions myUIOptions;
  private InspectionTreeState myTreeState;

  public GlobalInspectionContextImpl(@NotNull Project project, @NotNull NotNullLazyValue<ContentManager> contentManager) {
    super(project);
    myUIOptions = AnalysisUIOptions.getInstance(project).copy();
    myContentManager = contentManager;
  }

  @NotNull
  private ContentManager getContentManager() {
    return myContentManager.getValue();
  }

  public void setTreeState(InspectionTreeState treeState) {
    myTreeState = treeState;
  }

  public void addView(@NotNull InspectionResultsView view,
                                   @NotNull String title,
                                   boolean isOffline) {
    LOG.assertTrue(myContent == null, "GlobalInspectionContext is busy under other view now");
    myContentManager.getValue().addContentManagerListener(new ContentManagerAdapter() {
      @Override
      public void contentRemoved(ContentManagerEvent event) {
        if (event.getContent() == myContent) {
          if (myView != null) {
            close(false);
          }
          myContent = null;
        }
      }
    });

    myView = view;
    if (!isOffline) {
      myView.setUpdating(true);
    }
    if (myTreeState != null) {
      myView.getTree().setTreeState(myTreeState);
    }
    myContent = ContentFactory.SERVICE.getInstance().createContent(view, title, false);

    myContent.setDisposer(myView);

    ContentManager contentManager = getContentManager();
    contentManager.addContent(myContent);
    contentManager.setSelectedContent(myContent);

    ToolWindowManager.getInstance(getProject()).getToolWindow(ToolWindowId.INSPECTION).activate(null);
  }

  public void addView(@NotNull InspectionResultsView view) {
    addView(view, InspectionsBundle.message(view.isSingleInspectionRun() ?
                                            "inspection.results.for.inspection.toolwindow.title" :
                                            "inspection.results.for.profile.toolwindow.title",
                                            view.getCurrentProfileName(), getCurrentScope().getShortenName()), false);

  }

  @Override
  public void doInspections(@NotNull final AnalysisScope scope) {
    if (myContent != null) {
      getContentManager().removeContent(myContent, true);
    }
    super.doInspections(scope);
  }

  public void launchInspectionsOffline(@NotNull final AnalysisScope scope,
                                       @Nullable final String outputPath,
                                       final boolean runGlobalToolsOnly,
                                       @NotNull final List<File> inspectionsResults) {
    performInspectionsWithProgressAndExportResults(scope, runGlobalToolsOnly, true, outputPath, inspectionsResults);
  }

  public void performInspectionsWithProgressAndExportResults(@NotNull final AnalysisScope scope,
                                                             final boolean runGlobalToolsOnly,
                                                             final boolean isOfflineInspections,
                                                             @Nullable final String outputPath,
                                                             @NotNull final List<File> inspectionsResults) {
    cleanupTools();
    setCurrentScope(scope);

    final Runnable action = () -> {
      DefaultInspectionToolPresentation.setOutputPath(outputPath);
      try {
        performInspectionsWithProgress(scope, runGlobalToolsOnly, isOfflineInspections);
        exportResults(inspectionsResults, outputPath);
      }
      finally {
        DefaultInspectionToolPresentation.setOutputPath(null);
      }
    };
    if (isOfflineInspections) {
      ApplicationManager.getApplication().runReadAction(action);
    }
    else {
      action.run();
    }
  }

  private void exportResults(@NotNull List<File> inspectionsResults, @Nullable String outputPath) {
    @NonNls final String ext = ".xml";
    final Map<Element, Tools> globalTools = new HashMap<>();
    for (Map.Entry<String,Tools> entry : myTools.entrySet()) {
      final Tools sameTools = entry.getValue();
      boolean hasProblems = false;
      String toolName = entry.getKey();
      if (sameTools != null) {
        for (ScopeToolState toolDescr : sameTools.getTools()) {
          InspectionToolWrapper toolWrapper = toolDescr.getTool();
          if (toolWrapper instanceof LocalInspectionToolWrapper) {
            hasProblems = new File(outputPath, toolName + ext).exists();
          }
          else {
            InspectionToolPresentation presentation = getPresentation(toolWrapper);
            presentation.updateContent();
            if (presentation.hasReportedProblems()) {
              final Element root = new Element(InspectionsBundle.message("inspection.problems"));
              globalTools.put(root, sameTools);
              LOG.assertTrue(!hasProblems, toolName);
              break;
            }
          }
        }
      }
      if (hasProblems) {
        try {
          new File(outputPath).mkdirs();
          final File file = new File(outputPath, toolName + ext);
          inspectionsResults.add(file);
          FileUtil
            .writeToFile(file, ("</" + InspectionsBundle.message("inspection.problems") + ">").getBytes(CharsetToolkit.UTF8_CHARSET), true);
        }
        catch (IOException e) {
          LOG.error(e);
        }
      }
    }

    getRefManager().iterate(new RefVisitor() {
      @Override
      public void visitElement(@NotNull final RefEntity refEntity) {
        for (Map.Entry<Element, Tools> entry : globalTools.entrySet()) {
          Tools tools = entry.getValue();
          Element element = entry.getKey();
          for (ScopeToolState state : tools.getTools()) {
            try {
              InspectionToolWrapper toolWrapper = state.getTool();
              InspectionToolPresentation presentation = getPresentation(toolWrapper);
              presentation.exportResults(element, refEntity, d -> false);
            }
            catch (Throwable e) {
              LOG.error("Problem when exporting: " + refEntity.getExternalName(), e);
            }
          }
        }
      }
    });

    for (Map.Entry<Element, Tools> entry : globalTools.entrySet()) {
      final String toolName = entry.getValue().getShortName();
      Element element = entry.getKey();
      element.setAttribute(LOCAL_TOOL_ATTRIBUTE, Boolean.toString(false));
      final org.jdom.Document doc = new org.jdom.Document(element);
      PathMacroManager.getInstance(getProject()).collapsePaths(doc.getRootElement());
      try {
        new File(outputPath).mkdirs();
        final File file = new File(outputPath, toolName + ext);
        inspectionsResults.add(file);

        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), CharsetToolkit.UTF8_CHARSET)) {
          JDOMUtil.writeDocument(doc, writer, "\n");
        }
      }
      catch (IOException e) {
        LOG.error(e);
      }
    }
  }

  public void ignoreElement(@NotNull InspectionProfileEntry tool, @NotNull PsiElement element) {
    final RefElement refElement = getRefManager().getReference(element);
    final Tools tools = myTools.get(tool.getShortName());
    if (tools != null){
      for (ScopeToolState state : tools.getTools()) {
        InspectionToolWrapper toolWrapper = state.getTool();
        ignoreElementRecursively(toolWrapper, refElement);
      }
    }
  }

  public InspectionResultsView getView() {
    return myView;
  }

  private void ignoreElementRecursively(@NotNull InspectionToolWrapper toolWrapper, final RefEntity refElement) {
    if (refElement != null) {
      InspectionToolPresentation presentation = getPresentation(toolWrapper);
      presentation.ignoreCurrentElement(refElement);
      final List<RefEntity> children = refElement.getChildren();
      if (children != null) {
        for (RefEntity child : children) {
          ignoreElementRecursively(toolWrapper, child);
        }
      }
    }
  }

  @NotNull
  public AnalysisUIOptions getUIOptions() {
    return myUIOptions;
  }

  public void setSplitterProportion(final float proportion) {
    myUIOptions.SPLITTER_PROPORTION = proportion;
  }

  @NotNull
  public ToggleAction createToggleAutoscrollAction() {
    return myUIOptions.getAutoScrollToSourceHandler().createToggleAction();
  }

  @Override
  protected void launchInspections(@NotNull final AnalysisScope scope) {
    if (!ApplicationManager.getApplication().isUnitTestMode()) {
      myUIOptions = AnalysisUIOptions.getInstance(getProject()).copy();
    }
    myViewClosed = false;
    super.launchInspections(scope);
  }

  @NotNull
  @Override
  protected PerformInBackgroundOption createOption() {
    return new PerformAnalysisInBackgroundOption(getProject());
  }

  @Override
  protected void notifyInspectionsFinished(final AnalysisScope scope) {
    if (ApplicationManager.getApplication().isUnitTestMode()) return;
    UIUtil.invokeLaterIfNeeded(() -> {
      LOG.info("Code inspection finished");

      InspectionResultsView view = myView == null ? new InspectionResultsView(this, createContentProvider()) : null;
      if (!(myView == null ? view : myView).hasProblems()) {
        NOTIFICATION_GROUP.createNotification(InspectionsBundle.message("inspection.no.problems.message",
                                                                        scope.getFileCount(),
                                                                        scope.getShortenName()),
                                              MessageType.INFO).notify(getProject());
        close(true);
        if (view != null) {
          Disposer.dispose(view);
        }
      }
      else if (view != null) {
        addView(view);
        view.update();
      }
      if (myView != null) {
        myView.setUpdating(false);
      }
    });
  }

  @Override
  protected void runTools(@NotNull final AnalysisScope scope, boolean runGlobalToolsOnly, boolean isOfflineInspections) {
    final ProgressIndicator progressIndicator = ProgressIndicatorProvider.getGlobalProgressIndicator();
    if (progressIndicator == null) {
      throw new IncorrectOperationException("Must be run under progress");
    }
    if (!isOfflineInspections && ApplicationManager.getApplication().isDispatchThread()) {
      throw new IncorrectOperationException("Must not start inspections from within EDT");
    }
    if (ApplicationManager.getApplication().isWriteAccessAllowed()) {
      throw new IncorrectOperationException("Must not start inspections from within write action");
    }
    // in offline inspection application we don't care about global read action
    if (!isOfflineInspections && ApplicationManager.getApplication().isReadAccessAllowed()) {
      throw new IncorrectOperationException("Must not start inspections from within global read action");
    }
    final InspectionManager inspectionManager = InspectionManager.getInstance(getProject());
    ((RefManagerImpl)getRefManager()).initializeAnnotators();
    final List<Tools> globalTools = new ArrayList<>();
    final List<Tools> localTools = new ArrayList<>();
    final List<Tools> globalSimpleTools = new ArrayList<>();
    initializeTools(globalTools, localTools, globalSimpleTools);
    appendPairedInspectionsForUnfairTools(globalTools, globalSimpleTools, localTools);

    runGlobalTools(scope, inspectionManager, globalTools, isOfflineInspections);

    if (runGlobalToolsOnly || localTools.isEmpty() && globalSimpleTools.isEmpty()) return;

    final Set<VirtualFile> localScopeFiles = ReadAction.compute(() -> scope.toSearchScope()) instanceof LocalSearchScope ? new THashSet<>() : null;
    for (Tools tools : globalSimpleTools) {
      GlobalInspectionToolWrapper toolWrapper = (GlobalInspectionToolWrapper)tools.getTool();
      GlobalSimpleInspectionTool tool = (GlobalSimpleInspectionTool)toolWrapper.getTool();
      tool.inspectionStarted(inspectionManager, this, getPresentation(toolWrapper));
    }

    final boolean headlessEnvironment = ApplicationManager.getApplication().isHeadlessEnvironment();
    final Map<String, InspectionToolWrapper> map = getInspectionWrappersMap(localTools);

    final BlockingQueue<PsiFile> filesToInspect = new ArrayBlockingQueue<>(1000);
    // use original progress indicator here since we don't want it to cancel on write action start
    ProgressIndicator iteratingIndicator = new SensitiveProgressWrapper(progressIndicator);
    Future<?> future = startIterateScopeInBackground(scope, localScopeFiles, headlessEnvironment, filesToInspect, iteratingIndicator);

    Processor<PsiFile> processor = file -> {
      ProgressManager.checkCanceled();
      if (!ApplicationManagerEx.getApplicationEx().tryRunReadAction(() -> {
        if (!file.isValid()) {
          return;
        }
        LOG.assertTrue(scope.contains(file.getVirtualFile()), file.getName());
        inspectFile(file, inspectionManager, localTools, globalSimpleTools, map);
      })) {
        throw new ProcessCanceledException();
      }

      return true;
    };
    try {
      final Queue<PsiFile> filesFailedToInspect = new LinkedBlockingQueue<>();
      while (true) {
        Disposable disposable = Disposer.newDisposable();
        ProgressIndicator wrapper = new SensitiveProgressWrapper(progressIndicator);
        wrapper.start();
        ProgressIndicatorUtils.forceWriteActionPriority(wrapper, disposable);

        try {
          // use wrapper here to cancel early when write action start but do not affect the original indicator
          ((JobLauncherImpl)JobLauncher.getInstance()).processQueue(filesToInspect, filesFailedToInspect, wrapper, TOMBSTONE, processor);
          break;
        }
        catch (ProcessCanceledException ignored) {
          progressIndicator.checkCanceled();
          // PCE may be thrown from inside wrapper when write action started
          // go on with the write and then resume processing the rest of the queue
          assert !ApplicationManager.getApplication().isReadAccessAllowed();
          assert !ApplicationManager.getApplication().isDispatchThread();

          // wait for write action to complete
          ApplicationManager.getApplication().runReadAction(EmptyRunnable.getInstance());
        }
        finally {
          Disposer.dispose(disposable);
        }
      }
    }
    finally {
      iteratingIndicator.cancel(); // tell file scanning thread to stop
      filesToInspect.clear(); // let file scanning thread a chance to put TOMBSTONE and complete
      try {
        future.get(30, TimeUnit.SECONDS);
      }
      catch (Exception e) {
        LOG.error("Thread dump: \n"+ThreadDumper.dumpThreadsToString(), e);
      }
    }

    progressIndicator.checkCanceled();

    for (Tools tools : globalSimpleTools) {
      GlobalInspectionToolWrapper toolWrapper = (GlobalInspectionToolWrapper)tools.getTool();
      GlobalSimpleInspectionTool tool = (GlobalSimpleInspectionTool)toolWrapper.getTool();
      ProblemDescriptionsProcessor problemDescriptionProcessor = getProblemDescriptionProcessor(toolWrapper, map);
      tool.inspectionFinished(inspectionManager, this, problemDescriptionProcessor);

    }

    addProblemsToView(globalSimpleTools);
  }

  private void inspectFile(@NotNull final PsiFile file,
                              @NotNull final InspectionManager inspectionManager,
                              @NotNull List<Tools> localTools,
                              @NotNull List<Tools> globalSimpleTools,
                              @NotNull final Map<String, InspectionToolWrapper> wrappersMap) {
    Document document = PsiDocumentManager.getInstance(getProject()).getDocument(file);
    if (document == null) return;

    VirtualFile virtualFile = file.getVirtualFile();
    String url = ProjectUtilCore.displayUrlRelativeToProject(virtualFile, virtualFile.getPresentableUrl(), getProject(), true, false);
    incrementJobDoneAmount(getStdJobDescriptors().LOCAL_ANALYSIS, url);

    final LocalInspectionsPass pass = new LocalInspectionsPass(file, document, 0,
                                                               file.getTextLength(), LocalInspectionsPass.EMPTY_PRIORITY_RANGE, true,
                                                               HighlightInfoProcessor.getEmpty());
    try {
      boolean includeDoNotShow = includeDoNotShow(getCurrentProfile());
      final List<LocalInspectionToolWrapper> lTools = getWrappersFromTools(localTools, file, includeDoNotShow);
      pass.doInspectInBatch(this, inspectionManager, lTools);

      final List<GlobalInspectionToolWrapper> tools = getWrappersFromTools(globalSimpleTools, file, includeDoNotShow);
      JobLauncher.getInstance().invokeConcurrentlyUnderProgress(tools, myProgressIndicator, false, toolWrapper -> {
        GlobalSimpleInspectionTool tool = (GlobalSimpleInspectionTool)toolWrapper.getTool();
        ProblemsHolder holder = new ProblemsHolder(inspectionManager, file, false);
        ProblemDescriptionsProcessor problemDescriptionProcessor = getProblemDescriptionProcessor(toolWrapper, wrappersMap);
        tool.checkFile(file, inspectionManager, holder, this, problemDescriptionProcessor);
        InspectionToolPresentation toolPresentation = getPresentation(toolWrapper);
        LocalDescriptorsUtil.addProblemDescriptors(holder.getResults(), false, this, null, CONVERT, toolPresentation);
        return true;
      });
    }
    catch (ProcessCanceledException e) {
      final Throwable cause = e.getCause();
      if (cause == null) {
        throw e;
      }
      LOG.error("In file: " + file, cause);
    }
    catch (IndexNotReadyException e) {
      throw e;
    }
    catch (Throwable e) {
      LOG.error("In file: " + file.getName(), e);
    }
    finally {
      InjectedLanguageManager.getInstance(getProject()).dropFileCaches(file);
    }
  }

  protected boolean includeDoNotShow(final InspectionProfile profile) {
    return profile.getSingleTool() != null;
  }

  private static final PsiFile TOMBSTONE = PsiUtilCore.NULL_PSI_FILE;

  @NotNull
  private Future<?> startIterateScopeInBackground(@NotNull final AnalysisScope scope,
                                                  @Nullable final Collection<VirtualFile> localScopeFiles,
                                                  final boolean headlessEnvironment,
                                                  @NotNull final BlockingQueue<PsiFile> outFilesToInspect,
                                                  @NotNull final ProgressIndicator progressIndicator) {
    Task.Backgroundable task = new Task.Backgroundable(getProject(), "Scanning Files to Inspect") {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        try {
          final FileIndex fileIndex = ProjectRootManager.getInstance(getProject()).getFileIndex();
          scope.accept(file -> {
            indicator.checkCanceled();
            if (ProjectUtil.isProjectOrWorkspaceFile(file) || !fileIndex.isInContent(file)) return true;

            PsiFile psiFile = ApplicationManager.getApplication().runReadAction((Computable<PsiFile>)() -> {
              if (getProject().isDisposed()) throw new ProcessCanceledException();
              PsiFile psi = PsiManager.getInstance(getProject()).findFile(file);
              Document document = psi == null ? null : shouldProcess(psi, headlessEnvironment, localScopeFiles);
              if (document != null) {
                return psi;
              }
              return null;
            });
            //do not inspect binary files
            if (psiFile != null) {
              try {
                LOG.assertTrue(!ApplicationManager.getApplication().isReadAccessAllowed());
                outFilesToInspect.put(psiFile);
              }
              catch (InterruptedException e) {
                LOG.error(e);
              }
            }
            indicator.checkCanceled();
            return true;
          });
        }
        catch (ProcessCanceledException e) {
          // ignore, but put tombstone
        }
        finally {
          try {
            outFilesToInspect.put(TOMBSTONE);
          }
          catch (InterruptedException e) {
            LOG.error(e);
          }
        }
      }
    };
    return ((CoreProgressManager)ProgressManager.getInstance()).runProcessWithProgressAsynchronously(task, progressIndicator, null);
  }

  private Document shouldProcess(@NotNull PsiFile file, boolean headlessEnvironment, @Nullable Collection<VirtualFile> localScopeFiles) {
    final VirtualFile virtualFile = file.getVirtualFile();
    if (virtualFile == null) return null;
    if (isBinary(file)) return null; //do not inspect binary files

    if (myViewClosed && !headlessEnvironment) {
      throw new ProcessCanceledException();
    }

    if (LOG.isDebugEnabled()) {
      LOG.debug("Running local inspections on " + virtualFile.getPath());
    }

    if (SingleRootFileViewProvider.isTooLargeForIntelligence(virtualFile)) return null;
    if (localScopeFiles != null && !localScopeFiles.add(virtualFile)) return null;
    if (!ProblemHighlightFilter.shouldProcessFileInBatch(file)) return null;

    return PsiDocumentManager.getInstance(getProject()).getDocument(file);
  }

  private void runGlobalTools(@NotNull final AnalysisScope scope,
                              @NotNull final InspectionManager inspectionManager,
                              @NotNull List<Tools> globalTools,
                              boolean isOfflineInspections) {
    LOG.assertTrue(!ApplicationManager.getApplication().isReadAccessAllowed() || isOfflineInspections, "Must not run under read action, too unresponsive");
    final List<InspectionToolWrapper> needRepeatSearchRequest = new ArrayList<>();

    final boolean canBeExternalUsages = !(scope.getScopeType() == AnalysisScope.PROJECT && scope.isIncludeTestSource());
    for (Tools tools : globalTools) {
      for (ScopeToolState state : tools.getTools()) {
        final InspectionToolWrapper toolWrapper = state.getTool();
        final GlobalInspectionTool tool = (GlobalInspectionTool)toolWrapper.getTool();
        final InspectionToolPresentation toolPresentation = getPresentation(toolWrapper);
        try {
          if (tool.isGraphNeeded()) {
            try {
              ((RefManagerImpl)getRefManager()).findAllDeclarations();
            }
            catch (Throwable e) {
              getStdJobDescriptors().BUILD_GRAPH.setDoneAmount(0);
              throw e;
            }
          }
          ApplicationManager.getApplication().runReadAction(() -> {
            tool.runInspection(scope, inspectionManager, this, toolPresentation);
            //skip phase when we are sure that scope already contains everything, unused declaration though needs to proceed with its suspicious code
            if ((canBeExternalUsages || tool.getAdditionalJobs(this) != null) &&
                tool.queryExternalUsagesRequests(inspectionManager, this, toolPresentation)) {
              needRepeatSearchRequest.add(toolWrapper);
            }
          });
        }
        catch (ProcessCanceledException | IndexNotReadyException e) {
          throw e;
        }
        catch (Throwable e) {
          LOG.error(e);
        }
      }
    }

    for (GlobalInspectionContextExtension extension : myExtensions.values()) {
      try {
        extension.performPostRunActivities(needRepeatSearchRequest, this);
      }
      catch (ProcessCanceledException | IndexNotReadyException e) {
        throw e;
      }
      catch (Throwable e) {
        LOG.error(e);
      }
    }

    addProblemsToView(globalTools);
  }

  public ActionCallback initializeViewIfNeed() {
    if (myView != null) {
      return ActionCallback.DONE;
    }
    final Application app = ApplicationManager.getApplication();
    final Runnable createView = () -> {
      InspectionResultsView view = getView();
      if (view == null) {
        view = new InspectionResultsView(this, createContentProvider());
        addView(view);
      }
    };
    if (app.isUnitTestMode()) {
      createView.run();
      return ActionCallback.DONE;
    } else {
      return app.getInvokator().invokeLater(createView);
    }
  }

  private void appendPairedInspectionsForUnfairTools(@NotNull List<Tools> globalTools,
                                                     @NotNull List<Tools> globalSimpleTools,
                                                     @NotNull List<Tools> localTools) {
    Tools[] larray = localTools.toArray(new Tools[localTools.size()]);
    for (Tools tool : larray) {
      LocalInspectionToolWrapper toolWrapper = (LocalInspectionToolWrapper)tool.getTool();
      LocalInspectionTool localTool = toolWrapper.getTool();
      if (localTool instanceof PairedUnfairLocalInspectionTool) {
        String batchShortName = ((PairedUnfairLocalInspectionTool)localTool).getInspectionForBatchShortName();
        InspectionProfile currentProfile = getCurrentProfile();
        InspectionToolWrapper batchInspection;
        if (currentProfile == null) {
          batchInspection = null;
        }
        else {
          final InspectionToolWrapper pairedWrapper = currentProfile.getInspectionTool(batchShortName, getProject());
          batchInspection = pairedWrapper != null ? pairedWrapper.createCopy() : null;
        }
        if (batchInspection != null && !myTools.containsKey(batchShortName)) {
          // add to existing inspections to run
          InspectionProfileEntry batchTool = batchInspection.getTool();
          final ScopeToolState defaultState = tool.getDefaultState();
          ToolsImpl newTool = new ToolsImpl(batchInspection, defaultState.getLevel(), true, defaultState.isEnabled());
          for (ScopeToolState state : tool.getTools()) {
            final NamedScope scope = state.getScope(getProject());
            if (scope != null) {
              newTool.addTool(scope, batchInspection, state.isEnabled(), state.getLevel());
            }
          }
          if (batchTool instanceof LocalInspectionTool) localTools.add(newTool);
          else if (batchTool instanceof GlobalSimpleInspectionTool) globalSimpleTools.add(newTool);
          else if (batchTool instanceof GlobalInspectionTool) globalTools.add(newTool);
          else throw new AssertionError(batchTool);
          myTools.put(batchShortName, newTool);
          batchInspection.initialize(this);
        }
      }
    }
  }

  @NotNull
  private static <T extends InspectionToolWrapper> List<T> getWrappersFromTools(@NotNull List<Tools> localTools,
                                                                                @NotNull PsiFile file,
                                                                                boolean includeDoNotShow) {
    final List<T> lTools = new ArrayList<>();
    for (Tools tool : localTools) {
      //noinspection unchecked
      final T enabledTool = (T)tool.getEnabledTool(file, includeDoNotShow);
      if (enabledTool != null) {
        lTools.add(enabledTool);
      }
    }
    return lTools;
  }

  @NotNull
  private ProblemDescriptionsProcessor getProblemDescriptionProcessor(@NotNull final GlobalInspectionToolWrapper toolWrapper,
                                                                      @NotNull final Map<String, InspectionToolWrapper> wrappersMap) {
    return new ProblemDescriptionsProcessor() {
      @Override
      public void addProblemElement(@Nullable RefEntity refEntity, @NotNull CommonProblemDescriptor... commonProblemDescriptors) {
        for (CommonProblemDescriptor problemDescriptor : commonProblemDescriptors) {
          if (!(problemDescriptor instanceof ProblemDescriptor)) {
            continue;
          }
          ProblemGroup problemGroup = ((ProblemDescriptor)problemDescriptor).getProblemGroup();

          InspectionToolWrapper targetWrapper = problemGroup == null ? toolWrapper : wrappersMap.get(problemGroup.getProblemName());
          if (targetWrapper != null) { // Else it's switched off
            InspectionToolPresentation toolPresentation = getPresentation(targetWrapper);
            toolPresentation.addProblemElement(refEntity, problemDescriptor);
          }
        }
      }
    };
  }

  @NotNull
  private static Map<String, InspectionToolWrapper> getInspectionWrappersMap(@NotNull List<Tools> tools) {
    Map<String, InspectionToolWrapper> name2Inspection = new HashMap<>(tools.size());
    for (Tools tool : tools) {
      InspectionToolWrapper toolWrapper = tool.getTool();
      name2Inspection.put(toolWrapper.getShortName(), toolWrapper);
    }

    return name2Inspection;
  }

  private static final TripleFunction<LocalInspectionTool,PsiElement,GlobalInspectionContext,RefElement> CONVERT =
    (tool, elt, context) -> {
      PsiNamedElement problemElement = PsiTreeUtil.getNonStrictParentOfType(elt, PsiFile.class);

      RefElement refElement = context.getRefManager().getReference(problemElement);
      if (refElement == null && problemElement != null) {  // no need to lose collected results
        refElement = GlobalInspectionContextUtil.retrieveRefElement(elt, context);
      }
      return refElement;
    };


  @Override
  public void close(boolean noSuspisiousCodeFound) {
    if (!noSuspisiousCodeFound) {
      if (myView.isRerun()) {
        myViewClosed = true;
        myView = null;
      }
      if (myView == null) {
        return;
      }
    }
    AnalysisUIOptions.getInstance(getProject()).save(myUIOptions);
    if (myContent != null) {
      final ContentManager contentManager = getContentManager();
      contentManager.removeContent(myContent, true);
    }
    myViewClosed = true;
    myView = null;
    ((InspectionManagerEx)InspectionManager.getInstance(getProject())).closeRunningContext(this);
    for (Tools tools : myTools.values()) {
      for (ScopeToolState state : tools.getTools()) {
        InspectionToolWrapper toolWrapper = state.getTool();
        getPresentation(toolWrapper).finalCleanup();
      }
    }
    super.close(noSuspisiousCodeFound);
  }

  @Override
  public void cleanup() {
    if (myView != null) {
      myView.setUpdating(false);
    } else {
      myPresentationMap.clear();
      super.cleanup();
    }
  }

  public void refreshViews() {
    if (myView != null) {
      myView.getTree().queueUpdate();
    }
  }

  private final ConcurrentMap<InspectionToolWrapper, InspectionToolPresentation> myPresentationMap = ContainerUtil.newConcurrentMap();
  @NotNull
  public InspectionToolPresentation getPresentation(@NotNull InspectionToolWrapper toolWrapper) {
    InspectionToolPresentation presentation = myPresentationMap.get(toolWrapper);
    if (presentation == null) {
      String presentationClass = StringUtil.notNullize(toolWrapper.myEP == null ? null : toolWrapper.myEP.presentation, DefaultInspectionToolPresentation.class.getName());

      try {
        Constructor<?> constructor = Class.forName(presentationClass).getConstructor(InspectionToolWrapper.class, GlobalInspectionContextImpl.class);
        presentation = (InspectionToolPresentation)constructor.newInstance(toolWrapper, this);
      }
      catch (Exception e) {
        LOG.error(e);
        throw new RuntimeException(e);
      }
      presentation = ConcurrencyUtil.cacheOrGet(myPresentationMap, toolWrapper, presentation);
    }
    return presentation;
  }

  @Override
  public void codeCleanup(@NotNull final AnalysisScope scope,
                          @NotNull final InspectionProfile profile,
                          @Nullable final String commandName,
                          @Nullable final Runnable postRunnable,
                          final boolean modal) {
    String title = "Inspect Code...";
    Task task = modal ? new Task.Modal(getProject(), title, true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        cleanup(scope, profile, postRunnable, commandName);
      }
    } : new Task.Backgroundable(getProject(), title, true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        cleanup(scope, profile, postRunnable, commandName);
      }
    };
    ProgressManager.getInstance().run(task);
  }

  private void cleanup(@NotNull final AnalysisScope scope,
                       @NotNull InspectionProfile profile,
                       @Nullable final Runnable postRunnable,
                       @Nullable final String commandName) {
    setCurrentScope(scope);
    final int fileCount = scope.getFileCount();
    final ProgressIndicator progressIndicator = ProgressManager.getInstance().getProgressIndicator();

    final SearchScope searchScope = ReadAction.compute(() -> scope.toSearchScope());
    final TextRange range;
    if (searchScope instanceof LocalSearchScope) {
      final PsiElement[] elements = ((LocalSearchScope)searchScope).getScope();
      range = elements.length == 1 ? ApplicationManager.getApplication().runReadAction((Computable<TextRange>)elements[0]::getTextRange) : null;
    }
    else {
      range = null;
    }
    final Iterable<Tools> inspectionTools = ContainerUtil.filter(profile.getAllEnabledInspectionTools(getProject()), tools -> {
      assert tools != null;
      return tools.getTool().getTool() instanceof CleanupLocalInspectionTool;
    });
    boolean includeDoNotShow = includeDoNotShow(profile);
    final RefManagerImpl refManager = (RefManagerImpl)getRefManager();
    refManager.inspectionReadActionStarted();
    List<ProblemDescriptor> descriptors = new ArrayList<>();
    Set<PsiFile> files = new HashSet<>();
    try {
      final List<LocalInspectionToolWrapper> lTools = new ArrayList<>();
      scope.accept(new PsiElementVisitor() {
        private int myCount;
        @Override
        public void visitFile(PsiFile file) {
          if (progressIndicator != null) {
            progressIndicator.setFraction((double)++myCount / fileCount);
          }
          if (isBinary(file)) return;
          for (final Tools tools : inspectionTools) {
            final InspectionToolWrapper tool = tools.getEnabledTool(file, includeDoNotShow);
            if (tool instanceof LocalInspectionToolWrapper) {
              lTools.add((LocalInspectionToolWrapper)tool);
              tool.initialize(GlobalInspectionContextImpl.this);
            }
          }

          if (!lTools.isEmpty()) {
            final LocalInspectionsPass pass = new LocalInspectionsPass(file, PsiDocumentManager.getInstance(getProject()).getDocument(file), range != null ? range.getStartOffset() : 0,
                                                                       range != null ? range.getEndOffset() : file.getTextLength(), LocalInspectionsPass.EMPTY_PRIORITY_RANGE, true,
                                                                       HighlightInfoProcessor.getEmpty());
            Runnable runnable = () -> pass.doInspectInBatch(GlobalInspectionContextImpl.this, InspectionManager.getInstance(getProject()), lTools);
            ApplicationManager.getApplication().runReadAction(runnable);

            List<ProblemDescriptor> localDescriptors = new ArrayList<>();
            for (LocalInspectionToolWrapper tool : lTools) {
              for (CommonProblemDescriptor descriptor : getPresentation(tool).getProblemDescriptors()) {
                if (descriptor instanceof ProblemDescriptor) {
                  localDescriptors.add((ProblemDescriptor)descriptor);
                }
              }
            }

            if (searchScope instanceof LocalSearchScope) {
              for (Iterator<ProblemDescriptor> iterator = localDescriptors.iterator(); iterator.hasNext(); ) {
                final ProblemDescriptor descriptor = iterator.next();
                final TextRange infoRange = descriptor instanceof ProblemDescriptorBase ? ((ProblemDescriptorBase)descriptor).getTextRange() : null;
                if (infoRange != null && !((LocalSearchScope)searchScope).containsRange(file, infoRange)) {
                  iterator.remove();
                }
              }
            }
            if (!localDescriptors.isEmpty()) {
              CleanupInspectionIntention.sortDescriptions(localDescriptors);
              descriptors.addAll(localDescriptors);
              files.add(file);
            }
          }
        }
      });
    }
    finally {
      refManager.inspectionReadActionFinished();
    }

    if (files.isEmpty()) {
      GuiUtils.invokeLaterIfNeeded(() -> {
        if (commandName != null) {
          NOTIFICATION_GROUP.createNotification(InspectionsBundle.message("inspection.no.problems.message", scope.getFileCount(), scope.getDisplayName()), MessageType.INFO).notify(getProject());
        }
        if (postRunnable != null) {
          postRunnable.run();
        }
      }, ModalityState.defaultModalityState());
      return;
    }
    Runnable runnable = () -> {
      if (!FileModificationService.getInstance().preparePsiElementsForWrite(files)) return;
      CleanupInspectionIntention.applyFixesNoSort(getProject(), "Code Cleanup", descriptors, null);
    };
    TransactionGuard.submitTransaction(getProject(), runnable);
  }

  private static boolean isBinary(@NotNull PsiFile file) {
    return file instanceof PsiBinaryFile || file.getFileType().isBinary();
  }

  public boolean isViewClosed() {
    return myViewClosed;
  }

  private InspectionRVContentProvider createContentProvider() {
    return new InspectionRVContentProviderImpl(getProject());
  }

  private void addProblemsToView(List<Tools> tools) {
    if (ApplicationManager.getApplication().isUnitTestMode() || ApplicationManager.getApplication().isHeadlessEnvironment()) {
      return;
    }
    if (myView == null && !ReadAction.compute(() -> InspectionResultsView.hasProblems(tools, this, createContentProvider())).booleanValue()) {
      return;
    }
    initializeViewIfNeed().doWhenDone(() -> myView.addTools(tools));
  }
}
