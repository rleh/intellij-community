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

package com.intellij.profile.codeInspection.ui;

import com.intellij.codeHighlighting.HighlightDisplayLevel;
import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.codeInsight.daemon.impl.HighlightInfoType;
import com.intellij.codeInsight.daemon.impl.SeverityRegistrar;
import com.intellij.codeInsight.hint.HintUtil;
import com.intellij.codeInspection.InspectionsBundle;
import com.intellij.codeInspection.ex.*;
import com.intellij.icons.AllIcons;
import com.intellij.ide.CommonActionsManager;
import com.intellij.ide.DefaultTreeExpander;
import com.intellij.ide.TreeExpander;
import com.intellij.ide.ui.search.SearchUtil;
import com.intellij.ide.ui.search.SearchableOptionsRegistrar;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.profile.codeInspection.BaseInspectionProfileManager;
import com.intellij.profile.codeInspection.InspectionProfileManager;
import com.intellij.profile.codeInspection.ProjectInspectionProfileManager;
import com.intellij.profile.codeInspection.ui.filter.InspectionFilterAction;
import com.intellij.profile.codeInspection.ui.filter.InspectionsFilter;
import com.intellij.profile.codeInspection.ui.inspectionsTree.InspectionConfigTreeNode;
import com.intellij.profile.codeInspection.ui.inspectionsTree.InspectionsConfigTreeComparator;
import com.intellij.profile.codeInspection.ui.inspectionsTree.InspectionsConfigTreeRenderer;
import com.intellij.profile.codeInspection.ui.inspectionsTree.InspectionsConfigTreeTable;
import com.intellij.profile.codeInspection.ui.table.ScopesAndSeveritiesTable;
import com.intellij.psi.search.scope.packageSet.NamedScope;
import com.intellij.ui.*;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.Alarm;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.Convertor;
import com.intellij.util.containers.Queue;
import com.intellij.util.ui.JBInsets;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;
import gnu.trove.THashMap;
import gnu.trove.THashSet;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.List;

import com.intellij.util.containers.Queue;

public class SingleInspectionProfilePanel extends JPanel {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInspection.ex.InspectionToolsPanel");
  @NonNls private static final String INSPECTION_FILTER_HISTORY = "INSPECTION_FILTER_HISTORY";
  private static final String UNDER_CONSTRUCTION = InspectionsBundle.message("inspection.tool.description.under.construction.text");
  @NonNls private static final String EMPTY_HTML = "<html><body></body></html>";

  private static final float DIVIDER_PROPORTION_DEFAULT = 0.5f;

  private final Map<HighlightDisplayKey, ToolDescriptors> myInitialToolDescriptors = new THashMap<>();
  private final InspectionConfigTreeNode myRoot =
    new InspectionConfigTreeNode.Group(InspectionsBundle.message("inspection.root.node.title"));
  private final Alarm myAlarm = new Alarm();
  private final ProjectInspectionProfileManager myProjectProfileManager;
  private InspectionProfileModifiableModel myProfile;
  private JEditorPane myBrowser;
  private JPanel myOptionsPanel;
  private JPanel myInspectionProfilePanel = null;
  private FilterComponent myProfileFilter;
  private final InspectionsFilter myInspectionsFilter = new InspectionsFilter() {
    @Override
    protected void filterChanged() {
      filterTree(myProfileFilter.getFilter());
    }
  };
  private boolean myModified = false;
  private InspectionsConfigTreeTable myTreeTable;
  private TreeExpander myTreeExpander;
  private boolean myIsInRestore = false;

  private String[] myInitialScopesOrder;
  private Disposable myDisposable = new Disposable() {
    @Override
    public void dispose() {}
  };

  public SingleInspectionProfilePanel(@NotNull ProjectInspectionProfileManager projectProfileManager,
                                      @NotNull InspectionProfileModifiableModel profile) {
    super(new BorderLayout());
    myProjectProfileManager = projectProfileManager;
    myProfile = profile;
  }

  public Map<HighlightDisplayKey, ToolDescriptors> getInitialToolDescriptors() {
    return myInitialToolDescriptors;
  }

  private static VisibleTreeState getExpandedNodes(InspectionProfileImpl profile) {
    if (profile.isProjectLevel()) {
      return ProjectInspectionProfilesVisibleTreeState.getInstance(((ProjectInspectionProfileManager)profile.getProfileManager()).getProject()).getVisibleTreeState(profile);
    }
    else {
      return AppInspectionProfilesVisibleTreeState.getInstance().getVisibleTreeState(profile);
    }
  }

  private static InspectionConfigTreeNode findGroupNodeByPath(@NotNull String[] path, int idx, @NotNull InspectionConfigTreeNode node) {
    if (path.length == idx) {
      return node;
    }

    final String currentKey = path[idx];
    for (int i = 0; i < node.getChildCount(); i++) {
      final InspectionConfigTreeNode currentNode = (InspectionConfigTreeNode)node.getChildAt(i);
      if (Comparing.equal(currentNode.getGroupName(), currentKey)) {
        return findGroupNodeByPath(path, ++idx, currentNode);
      }
    }

    return null;
  }

  @Nullable
  private static InspectionConfigTreeNode findNodeByKey(String name, InspectionConfigTreeNode root) {
    for (int i = 0; i < root.getChildCount(); i++) {
      final InspectionConfigTreeNode child = (InspectionConfigTreeNode)root.getChildAt(i);
      final Descriptor descriptor = child.getDefaultDescriptor();
      if (descriptor != null) {
        if (descriptor.getKey().toString().equals(name)) {
          return child;
        }
      }
      else {
        final InspectionConfigTreeNode node = findNodeByKey(name, child);
        if (node != null) return node;
      }
    }
    return null;
  }

  public static String renderSeverity(HighlightSeverity severity) {
    if (HighlightSeverity.INFORMATION.equals(severity)) return "No highlighting, only fix"; //todo severity presentation
    return StringUtil.capitalizeWords(severity.getName().toLowerCase(Locale.US), true);
  }

  private static void updateUpHierarchy(final InspectionConfigTreeNode parent) {
    if (parent != null) {
      parent.dropCache();
      updateUpHierarchy((InspectionConfigTreeNode)parent.getParent());
    }
  }

  private static boolean isDescriptorAccepted(Descriptor descriptor,
                                              @NonNls String filter,
                                              final boolean forceInclude,
                                              final List<Set<String>> keySetList, final Set<String> quoted) {
    filter = filter.toLowerCase();
    if (StringUtil.containsIgnoreCase(descriptor.getText(), filter)) {
      return true;
    }
    final String[] groupPath = descriptor.getGroup();
    for (String group : groupPath) {
      if (StringUtil.containsIgnoreCase(group, filter)) {
        return true;
      }
    }
    for (String stripped : quoted) {
      if (StringUtil.containsIgnoreCase(descriptor.getText(),stripped)) {
        return true;
      }
      for (String group : groupPath) {
        if (StringUtil.containsIgnoreCase(group,stripped)) {
          return true;
        }
      }
      final String description = descriptor.getToolWrapper().loadDescription();
      if (description != null && StringUtil.containsIgnoreCase(description.toLowerCase(Locale.US), stripped)) {
        if (!forceInclude) return true;
      } else if (forceInclude) return false;
    }
    for (Set<String> keySet : keySetList) {
      if (keySet.contains(descriptor.getKey().toString())) {
        if (!forceInclude) {
          return true;
        }
      }
      else {
        if (forceInclude) {
          return false;
        }
      }
    }
    return forceInclude;
  }

  private static void setConfigPanel(final JPanel configPanelAnchor, final ScopeToolState state) {
    configPanelAnchor.removeAll();
    final JComponent additionalConfigPanel = state.getAdditionalConfigPanel();
    if (additionalConfigPanel != null) {
      // assume that the panel does not need scrolling if it already contains a scrollable content
      if (UIUtil.hasScrollPane(additionalConfigPanel)) {
        configPanelAnchor.add(additionalConfigPanel);
      }
      else {
        configPanelAnchor.add(ScrollPaneFactory.createScrollPane(additionalConfigPanel, SideBorder.NONE));
      }
    }
    UIUtil.setEnabled(configPanelAnchor, state.isEnabled(), true);
  }

  private static InspectionConfigTreeNode getGroupNode(InspectionConfigTreeNode root, String[] groupPath) {
    InspectionConfigTreeNode currentRoot = root;
    for (final String group : groupPath) {
      currentRoot = getGroupNode(currentRoot, group);
    }
    return currentRoot;
  }

  private static InspectionConfigTreeNode getGroupNode(InspectionConfigTreeNode root, String group) {
    final int childCount = root.getChildCount();
    for (int i = 0; i < childCount; i++) {
      InspectionConfigTreeNode child = (InspectionConfigTreeNode)root.getChildAt(i);
      if (group.equals(child.getUserObject())) {
        return child;
      }
    }
    InspectionConfigTreeNode child = new InspectionConfigTreeNode.Group(group);
    root.add(child);
    return child;
  }

  private static void copyUsedSeveritiesIfUndefined(InspectionProfileImpl selectedProfile, BaseInspectionProfileManager profileManager) {
    final SeverityRegistrar registrar = profileManager.getSeverityRegistrar();
    final Set<HighlightSeverity> severities = selectedProfile.getUsedSeverities();
    for (Iterator<HighlightSeverity> iterator = severities.iterator(); iterator.hasNext();) {
      HighlightSeverity severity = iterator.next();
      if (registrar.isSeverityValid(severity.getName())) {
        iterator.remove();
      }
    }

    if (!severities.isEmpty()) {
      final SeverityRegistrar oppositeRegister = selectedProfile.getProfileManager().getSeverityRegistrar();
      for (HighlightSeverity severity : severities) {
        final TextAttributesKey attributesKey = TextAttributesKey.find(severity.getName());
        final TextAttributes textAttributes = oppositeRegister.getTextAttributesBySeverity(severity);
        if (textAttributes == null) {
          continue;
        }
        HighlightInfoType.HighlightInfoTypeImpl info = new HighlightInfoType.HighlightInfoTypeImpl(severity, attributesKey);
        registrar.registerSeverity(new SeverityRegistrar.SeverityBasedTextAttributes(textAttributes.clone(), info),
                                   textAttributes.getErrorStripeColor());
      }
    }
  }

  private void initUI() {
    myInspectionProfilePanel = createInspectionProfileSettingsPanel();
    add(myInspectionProfilePanel, BorderLayout.CENTER);
    UserActivityWatcher userActivityWatcher = new UserActivityWatcher();
    userActivityWatcher.addUserActivityListener(new UserActivityListener() {
      @Override
      public void stateChanged() {
        //invoke after all other listeners
        ApplicationManager.getApplication().invokeLater(() -> {
          if (myProfile == null) return; //panel was disposed
          updateProperSettingsForSelection();
          wereToolSettingsModified();
        });
      }
    });
    userActivityWatcher.register(myOptionsPanel);
    updateSelectedProfileState();
    reset();
  }

  private void updateSelectedProfileState() {
    if (myProfile == null) return;
    restoreTreeState();
    repaintTableData();
    updateSelection();
  }

  public void updateSelection() {
    if (myTreeTable != null) {
      final TreePath selectionPath = myTreeTable.getTree().getSelectionPath();
      if (selectionPath != null) {
        TreeUtil.selectNode(myTreeTable.getTree(), (TreeNode) selectionPath.getLastPathComponent());
        final int rowForPath = myTreeTable.getTree().getRowForPath(selectionPath);
        TableUtil.selectRows(myTreeTable, new int[]{rowForPath});
        scrollToCenter();
      }
    }
  }

  private void loadDescriptorsConfigs(boolean onlyModified) {
    for (ToolDescriptors toolDescriptors : myInitialToolDescriptors.values()) {
      loadDescriptorConfig(toolDescriptors.getDefaultDescriptor(), onlyModified);
      for (Descriptor descriptor : toolDescriptors.getNonDefaultDescriptors()) {
        loadDescriptorConfig(descriptor, onlyModified);
      }
    }
  }

  private void loadDescriptorConfig(Descriptor descriptor, boolean ifModifier) {
    if (!ifModifier || myProfile.isProperSetting(descriptor.getKey().toString())) {
      descriptor.loadConfig();
    }
  }

  private void wereToolSettingsModified() {
    for (final ToolDescriptors toolDescriptor : myInitialToolDescriptors.values()) {
      Descriptor desc = toolDescriptor.getDefaultDescriptor();
      if (wereToolSettingsModified(desc, true)) return;
      List<Descriptor> descriptors = toolDescriptor.getNonDefaultDescriptors();
      for (Descriptor descriptor : descriptors) {
        if (wereToolSettingsModified(descriptor, false)) return;
      }
    }
    myModified = false;
  }

  private boolean wereToolSettingsModified(Descriptor descriptor, boolean isDefault) {
    if (!myProfile.isToolEnabled(descriptor.getKey(), descriptor.getScope(), myProjectProfileManager.getProject())) {
      return false;
    }
    Element oldConfig = descriptor.getConfig();
    if (oldConfig == null) return false;

    ScopeToolState state = null;
    if (isDefault) {
      state = myProfile.getToolDefaultState(descriptor.getKey().toString(), myProjectProfileManager.getProject());
    } else {
      for (ScopeToolState candidate : myProfile.getNonDefaultTools(descriptor.getKey().toString(), myProjectProfileManager.getProject())) {
        final String scope = descriptor.getScopeName();
        if (Comparing.equal(candidate.getScopeName(), scope)) {
          state = candidate;
          break;
        }
      }
    }

    if (state == null) {
      return true;
    }

    Element newConfig = Descriptor.createConfigElement(state.getTool());
    if (!JDOMUtil.areElementsEqual(oldConfig, newConfig)) {
      myAlarm.cancelAllRequests();
      myAlarm.addRequest(() -> myTreeTable.repaint(), 300);
      myModified = true;
      return true;
    }
    return false;
  }

  private void updateProperSettingsForSelection() {
    final TreePath selectionPath = myTreeTable.getTree().getSelectionPath();
    if (selectionPath != null) {
      InspectionConfigTreeNode node = (InspectionConfigTreeNode)selectionPath.getLastPathComponent();
      final Descriptor descriptor = node.getDefaultDescriptor();
      if (descriptor != null) {
        final boolean properSetting = myProfile.isProperSetting(descriptor.getKey().toString());
        if (node.isProperSetting() != properSetting) {
          myAlarm.cancelAllRequests();
          myAlarm.addRequest(() -> myTreeTable.repaint(), 300);
          node.dropCache();
          updateUpHierarchy((InspectionConfigTreeNode)node.getParent());
        }
      }
    }
  }

  private void initToolStates() {
    InspectionProfileModifiableModel profile = myProfile;
    if (profile == null) {
      return;
    }

    myInitialToolDescriptors.clear();
    final Project project = myProjectProfileManager.getProject();
    for (final ScopeToolState state : profile.getDefaultStates(myProjectProfileManager.getProject())) {
      if (!accept(state.getTool())) {
        continue;
      }
      ToolDescriptors descriptors = ToolDescriptors.fromScopeToolState(state, profile, project);
      myInitialToolDescriptors.put(descriptors.getDefaultDescriptor().getKey(), descriptors);
    }
    myInitialScopesOrder = myProfile.getScopesOrder();
  }

  protected boolean accept(InspectionToolWrapper entry) {
    return entry.getDefaultLevel() != HighlightDisplayLevel.NON_SWITCHABLE_ERROR;
  }

  private void postProcessModification() {
    wereToolSettingsModified();
    //resetup configs
    for (ScopeToolState state : myProfile.getAllTools(myProjectProfileManager.getProject())) {
      state.resetConfigPanel();
    }
    fillTreeData(myProfileFilter.getFilter(), true);
    repaintTableData();
    updateOptionsAndDescriptionPanel(myTreeTable.getTree().getSelectionPaths());
  }

  public void setFilter(String filter) {
    myProfileFilter.setFilter(filter);
  }

  private void filterTree(@Nullable String filter) {
    if (myTreeTable != null) {
      getExpandedNodes(myProfile).saveVisibleState(myTreeTable.getTree());
      fillTreeData(filter, true);
      reloadModel();
      restoreTreeState();
      if (myTreeTable.getTree().getSelectionPath() == null) {
        TreeUtil.selectFirstNode(myTreeTable.getTree());
      }
    }
  }

  private void filterTree() {
    filterTree(myProfileFilter != null ? myProfileFilter.getFilter() : null);
  }

  private void reloadModel() {
    try {
      myIsInRestore = true;
      ((DefaultTreeModel)myTreeTable.getTree().getModel()).reload();
    }
    finally {
      myIsInRestore = false;
    }

  }

  private void restoreTreeState() {

    try {
      myIsInRestore = true;
      getExpandedNodes(myProfile).restoreVisibleState(myTreeTable.getTree());
    }
    finally {
      myIsInRestore = false;
    }
  }

  private ActionToolbar createTreeToolbarPanel() {
    final CommonActionsManager actionManager = CommonActionsManager.getInstance();

    DefaultActionGroup actions = new DefaultActionGroup();

    actions.add(new InspectionFilterAction(myProfile, myInspectionsFilter, myProjectProfileManager.getProject(), myProfileFilter));
    actions.addSeparator();

    actions.add(actionManager.createExpandAllAction(myTreeExpander, myTreeTable));
    actions.add(actionManager.createCollapseAllAction(myTreeExpander, myTreeTable));
    actions.add(new DumbAwareAction("Reset to Empty", "Reset to empty", AllIcons.Actions.Reset_to_empty){

      @Override
      public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(myProfile != null && myProfile.isExecutable(myProjectProfileManager.getProject()));
      }

      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        myProfile.resetToEmpty(e.getProject());
        loadDescriptorsConfigs(false);
        postProcessModification();
      }
    });

    actions.add(new AdvancedSettingsAction(myProjectProfileManager.getProject(), myRoot) {
      @Override
      protected InspectionProfileModifiableModel getInspectionProfile() {
        return myProfile;
      }

      @Override
      protected void postProcessModification() {
        loadDescriptorsConfigs(true);
        SingleInspectionProfilePanel.this.postProcessModification();
      }
    });


    final ActionToolbar actionToolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.UNKNOWN, actions, true);
    actionToolbar.setTargetComponent(this);
    return actionToolbar;
  }

  private void repaintTableData() {
    if (myTreeTable != null) {
      getExpandedNodes(myProfile).saveVisibleState(myTreeTable.getTree());
      reloadModel();
      restoreTreeState();
    }
  }

  public void selectInspectionTool(String name) {
    selectNode(findNodeByKey(name, myRoot));
  }

  public void selectInspectionGroup(String[] path) {
    final InspectionConfigTreeNode node = findGroupNodeByPath(path, 0, myRoot);
    selectNode(node);
    if (node != null) {
      myTreeTable.getTree().expandPath(new TreePath(node.getPath()));
    }
  }

  private void selectNode(InspectionConfigTreeNode node) {
    if (node != null) {
      TreeUtil.selectNode(myTreeTable.getTree(), node);
      final int rowForPath = myTreeTable.getTree().getRowForPath(new TreePath(node.getPath()));
      TableUtil.selectRows(myTreeTable, new int[]{rowForPath});
      scrollToCenter();
    }
  }

  private void scrollToCenter() {
    ListSelectionModel selectionModel = myTreeTable.getSelectionModel();
    int maxSelectionIndex = selectionModel.getMaxSelectionIndex();
    final int maxColumnSelectionIndex = Math.max(0, myTreeTable.getColumnModel().getSelectionModel().getMinSelectionIndex());
    Rectangle maxCellRect = myTreeTable.getCellRect(maxSelectionIndex, maxColumnSelectionIndex, false);

    final Point selectPoint = maxCellRect.getLocation();
    final int allHeight = myTreeTable.getVisibleRect().height;
    myTreeTable.scrollRectToVisible(new Rectangle(new Point(0, Math.max(0, selectPoint.y - allHeight / 2)), new Dimension(0, allHeight)));
  }

  private JScrollPane initTreeScrollPane() {
    fillTreeData(null, true);

    final InspectionsConfigTreeRenderer renderer = new InspectionsConfigTreeRenderer(){
      @Override
      protected String getFilter() {
        return myProfileFilter != null ? myProfileFilter.getFilter() : null;
      }
    };
    myTreeTable = InspectionsConfigTreeTable.create(new InspectionsConfigTreeTable.InspectionsConfigTreeTableSettings(myRoot, myProjectProfileManager.getProject()) {
      @Override
      protected void onChanged(final InspectionConfigTreeNode node) {
        updateUpHierarchy((InspectionConfigTreeNode)node.getParent());
      }

      @Override
      public void updateRightPanel() {
        updateOptionsAndDescriptionPanel();
      }

      @Override
      public InspectionProfileImpl getInspectionProfile() {
        return myProfile;
      }
    }, myDisposable);
    myTreeTable.setTreeCellRenderer(renderer);
    myTreeTable.setRootVisible(false);
    UIUtil.setLineStyleAngled(myTreeTable.getTree());
    TreeUtil.installActions(myTreeTable.getTree());


    myTreeTable.getTree().addTreeSelectionListener(new TreeSelectionListener() {
      @Override
      public void valueChanged(TreeSelectionEvent e) {
        if (myTreeTable.getTree().getSelectionPaths() != null) {
          updateOptionsAndDescriptionPanel(myTreeTable.getTree().getSelectionPaths());
        }
        else {
          initOptionsAndDescriptionPanel();
        }

        if (!myIsInRestore) {
          InspectionProfileModifiableModel selected = myProfile;
          if (selected != null) {
            InspectionProfileImpl baseProfile = selected.getSource();
            getExpandedNodes(baseProfile).setSelectionPaths(myTreeTable.getTree().getSelectionPaths());
            getExpandedNodes(selected).setSelectionPaths(myTreeTable.getTree().getSelectionPaths());
          }
        }
      }
    });


    myTreeTable.addMouseListener(new PopupHandler() {
      @Override
      public void invokePopup(Component comp, int x, int y) {
        final int[] selectionRows = myTreeTable.getTree().getSelectionRows();
        if (selectionRows != null &&
            myTreeTable.getTree().getPathForLocation(x, y) != null &&
            Arrays.binarySearch(selectionRows, myTreeTable.getTree().getRowForLocation(x, y)) > -1) {
          compoundPopup().show(comp, x, y);
        }
      }
    });


    new TreeSpeedSearch(myTreeTable.getTree(), new Convertor<TreePath, String>() {
      @Override
      public String convert(TreePath o) {
        final InspectionConfigTreeNode node = (InspectionConfigTreeNode)o.getLastPathComponent();
        final Descriptor descriptor = node.getDefaultDescriptor();
        return InspectionsConfigTreeComparator.getDisplayTextToSort(descriptor != null ? descriptor.getText() : node.getGroupName());
      }
    });


    final JScrollPane scrollPane = ScrollPaneFactory.createScrollPane(myTreeTable);
    myTreeTable.getTree().setShowsRootHandles(true);
    scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    scrollPane.setBorder(IdeBorderFactory.createBorder(SideBorder.BOTTOM + SideBorder.LEFT + SideBorder.TOP));
    TreeUtil.collapseAll(myTreeTable.getTree(), 1);

    myTreeTable.getTree().addTreeExpansionListener(new TreeExpansionListener() {


      @Override
      public void treeCollapsed(TreeExpansionEvent event) {
        InspectionProfileModifiableModel selected = myProfile;
        getExpandedNodes(selected.getSource()).saveVisibleState(myTreeTable.getTree());
        getExpandedNodes(selected).saveVisibleState(myTreeTable.getTree());
      }

      @Override
      public void treeExpanded(TreeExpansionEvent event) {
        InspectionProfileModifiableModel selected = myProfile;
        if (selected != null) {
          final InspectionConfigTreeNode node = (InspectionConfigTreeNode)event.getPath().getLastPathComponent();
          getExpandedNodes(selected.getSource()).expandNode(node);
          getExpandedNodes(selected).expandNode(node);
        }
      }
    });

    myTreeExpander = new DefaultTreeExpander(myTreeTable.getTree()) {
      @Override
      public boolean canExpand() {
        return myTreeTable.isShowing();
      }

      @Override
      public boolean canCollapse() {
        return myTreeTable.isShowing();
      }
    };
    myProfileFilter = new MyFilterComponent();

    return scrollPane;
  }

  private JPopupMenu compoundPopup() {
    final DefaultActionGroup group = new DefaultActionGroup();
    final SeverityRegistrar severityRegistrar = myProfile.getProfileManager().getOwnSeverityRegistrar();
    for (HighlightSeverity severity : LevelChooserAction.getSeverities(severityRegistrar, includeDoNotShow())) {
      final HighlightDisplayLevel level = HighlightDisplayLevel.find(severity);
      group.add(new AnAction(renderSeverity(severity), renderSeverity(severity), level.getIcon()) {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
          setNewHighlightingLevel(level);
        }

        @Override
        public boolean isDumbAware() {
          return true;
        }
      });
    }
    group.add(Separator.getInstance());
    ActionPopupMenu menu = ActionManager.getInstance().createActionPopupMenu(ActionPlaces.UNKNOWN, group);
    return menu.getComponent();
  }

  private boolean includeDoNotShow() {
    final TreePath[] paths = myTreeTable.getTree().getSelectionPaths();
    if (paths == null) return true;
    return includeDoNotShow(InspectionsAggregationUtil.getInspectionsNodes(paths));
  }

  private boolean includeDoNotShow(List<InspectionConfigTreeNode> nodes) {
    final Project project = myProjectProfileManager.getProject();
    return !nodes.stream()
      .filter(node -> myProfile.getToolDefaultState(node.getKey().toString(), project).getTool() instanceof GlobalInspectionToolWrapper)
      .findFirst()
      .isPresent();
  }

  private void fillTreeData(@Nullable String filter, boolean forceInclude) {
    if (myProfile == null) return;
    myRoot.removeAllChildren();
    myRoot.dropCache();
    List<Set<String>> keySetList = new ArrayList<>();
    final Set<String> quoted = new HashSet<>();
    if (filter != null && !filter.isEmpty()) {
      keySetList.addAll(SearchUtil.findKeys(filter, quoted));
    }
    Project project = myProjectProfileManager.getProject();
    final boolean emptyFilter = myInspectionsFilter.isEmptyFilter();
    for (ToolDescriptors toolDescriptors : myInitialToolDescriptors.values()) {
      final Descriptor descriptor = toolDescriptors.getDefaultDescriptor();
      if (filter != null && !filter.isEmpty() && !isDescriptorAccepted(descriptor, filter, forceInclude, keySetList, quoted)) {
        continue;
      }
      final InspectionConfigTreeNode node = new InspectionConfigTreeNode.Tool(toolDescriptors.getDefaultDescriptor().getKey(), this);
      if (!emptyFilter && !myInspectionsFilter.matches(
        myProfile.getTools(toolDescriptors.getDefaultDescriptor().getKey().toString(), project), node)) {
        continue;
      }
      getGroupNode(myRoot, toolDescriptors.getDefaultDescriptor().getGroup()).add(node);
      myRoot.dropCache();
    }
    if (filter != null && forceInclude && myRoot.getChildCount() == 0) {
      final Set<String> filters = SearchableOptionsRegistrar.getInstance().getProcessedWords(filter);
      if (filters.size() > 1 || !quoted.isEmpty()) {
        fillTreeData(filter, false);
      }
    }
    TreeUtil.sort(myRoot, new InspectionsConfigTreeComparator());
  }

  // TODO 134099: see IntentionDescriptionPanel#readHTML
  public static boolean readHTML(JEditorPane browser, String text) {
    try {
      browser.read(new StringReader(text), null);
      return true;
    }
    catch (IOException ignored) {
      return false;
    }
  }

  // TODO 134099: see IntentionDescriptionPanel#setHTML
  public static String toHTML(JEditorPane browser, String text, boolean miniFontSize) {
    final HintHint hintHint = new HintHint(browser, new Point(0, 0));
    hintHint.setFont(miniFontSize ? UIUtil.getLabelFont(UIUtil.FontSize.SMALL) : UIUtil.getLabelFont());
    return HintUtil.prepareHintText(text, hintHint);
  }

  private void updateOptionsAndDescriptionPanel(final TreePath... paths) {
    if (myProfile == null || paths == null || paths.length == 0) {
      return;
    }
    final TreePath path = paths[0];
    if (path == null) return;
    final List<InspectionConfigTreeNode> nodes = InspectionsAggregationUtil.getInspectionsNodes(paths);
    if (!nodes.isEmpty()) {
      final InspectionConfigTreeNode singleNode = paths.length == 1 && ((InspectionConfigTreeNode)paths[0].getLastPathComponent()).getDefaultDescriptor() != null
                                                  ? ContainerUtil.getFirstItem(nodes) : null;
      if (singleNode != null) {
        final Descriptor descriptor = singleNode.getDefaultDescriptor();
        LOG.assertTrue(descriptor != null);
        if (descriptor.loadDescription() != null) {
          // need this in order to correctly load plugin-supplied descriptions
          final Descriptor defaultDescriptor = singleNode.getDefaultDescriptor();
          final String description = defaultDescriptor.loadDescription();
          try {
            if (!readHTML(myBrowser, SearchUtil.markup(toHTML(myBrowser, description, false), myProfileFilter.getFilter()))) {
              readHTML(myBrowser, toHTML(myBrowser, "<b>" + UNDER_CONSTRUCTION + "</b>", false));
            }
          }
          catch (Throwable t) {
            LOG.error("Failed to load description for: " +
                      defaultDescriptor.getToolWrapper().getTool().getClass() +
                      "; description: " +
                      description, t);
          }

        }
        else {
          readHTML(myBrowser, toHTML(myBrowser, "Can't find inspection description.", false));
        }
      }
      else {
        readHTML(myBrowser, toHTML(myBrowser, "Multiple inspections are selected. You can edit them as a single inspection.", false));
      }

      myOptionsPanel.removeAll();
      final Project project = myProjectProfileManager.getProject();
      final JPanel severityPanel = new JPanel(new GridBagLayout());
      final JPanel configPanelAnchor = new JPanel(new GridLayout());

      final Set<String> scopesNames = new THashSet<>();
      for (final InspectionConfigTreeNode node : nodes) {
        final List<ScopeToolState> nonDefaultTools = myProfile.getNonDefaultTools(node.getDefaultDescriptor().getKey().toString(), project);
        for (final ScopeToolState tool : nonDefaultTools) {
          scopesNames.add(tool.getScopeName());
        }
      }

      final double severityPanelWeightY;
      if (scopesNames.isEmpty()) {

        final LevelChooserAction severityLevelChooser =
          new LevelChooserAction(myProfile.getProfileManager().getOwnSeverityRegistrar(),
                                 includeDoNotShow(nodes)) {
            @Override
            protected void onChosen(final HighlightSeverity severity) {
              final HighlightDisplayLevel level = HighlightDisplayLevel.find(severity);
              for (final InspectionConfigTreeNode node : nodes) {
                final HighlightDisplayKey key = node.getDefaultDescriptor().getKey();
                final NamedScope scope = node.getDefaultDescriptor().getScope();
                final boolean toUpdate = myProfile.getErrorLevel(key, scope, project) != level;
                myProfile.setErrorLevel(key, level, null, project);
                if (toUpdate) node.dropCache();
              }
              myTreeTable.updateUI();
            }
          };
        final HighlightSeverity severity =
          ScopesAndSeveritiesTable.getSeverity(ContainerUtil.map(nodes, node -> node.getDefaultDescriptor().getState()));
        severityLevelChooser.setChosen(severity);

        final ScopesChooser scopesChooser = new ScopesChooser(ContainerUtil.map(nodes, node -> node.getDefaultDescriptor()), myProfile, project, null) {
          @Override
          protected void onScopesOrderChanged() {
            myTreeTable.updateUI();
            updateOptionsAndDescriptionPanel();
          }

          @Override
          protected void onScopeAdded() {
            myTreeTable.updateUI();
            updateOptionsAndDescriptionPanel();
          }
        };

        severityPanel.add(new JLabel(InspectionsBundle.message("inspection.severity")),
                          new GridBagConstraints(0, 0, 1, 1, 0, 0, GridBagConstraints.WEST, GridBagConstraints.VERTICAL,
                                                 JBUI.insets(10, 0), 0, 0));
        final JComponent severityLevelChooserComponent = severityLevelChooser.createCustomComponent(severityLevelChooser.getTemplatePresentation());
        severityPanel.add(severityLevelChooserComponent,
                          new GridBagConstraints(1, 0, 1, 1, 0, 1, GridBagConstraints.WEST, GridBagConstraints.BOTH,
                                                 JBUI.insets(10, 0), 0, 0));
        final JComponent scopesChooserComponent = scopesChooser.createCustomComponent(scopesChooser.getTemplatePresentation());
        severityPanel.add(scopesChooserComponent,
                          new GridBagConstraints(2, 0, 1, 1, 0, 1, GridBagConstraints.WEST, GridBagConstraints.BOTH,
                                                 JBUI.insets(10, 0), 0, 0));
        final JLabel label = new JLabel("", SwingConstants.RIGHT);
        severityPanel.add(label,
                          new GridBagConstraints(3, 0, 1, 1, 1, 0,
                                                 GridBagConstraints.EAST,
                                                 GridBagConstraints.BOTH,
                                                 JBUI.insets(2, 0), 0, 0));
        severityPanelWeightY = 0.0;
        if (singleNode != null) {
          setConfigPanel(configPanelAnchor, myProfile.getToolDefaultState(singleNode.getDefaultDescriptor().getKey().toString(),
                                                                                  project));
        }
      }
      else {
        if (singleNode != null) {
          for (final Descriptor descriptor : singleNode.getDescriptors().getNonDefaultDescriptors()) {
            descriptor.loadConfig();
          }
        }
        final JTable scopesAndScopesAndSeveritiesTable =
          new ScopesAndSeveritiesTable(new ScopesAndSeveritiesTable.TableSettings(nodes, myProfile, project) {
            @Override
            protected void onScopeChosen(@NotNull final ScopeToolState state) {
              setConfigPanel(configPanelAnchor, state);
              configPanelAnchor.revalidate();
              configPanelAnchor.repaint();
            }

            @Override
            protected void onSettingsChanged() {
              update(false);
            }

            @Override
            protected void onScopeAdded() {
              update(true);
            }

            @Override
            protected void onScopesOrderChanged() {
              update(true);
            }

            @Override
            protected void onScopeRemoved(final int scopesCount) {
              update(scopesCount == 1);
            }

            private void update(final boolean updateOptionsAndDescriptionPanel) {
              Queue<InspectionConfigTreeNode> q = new Queue<>(nodes.size());
              for (InspectionConfigTreeNode node : nodes) {
                q.addLast(node);
              }
              while (!q.isEmpty()) {
                final InspectionConfigTreeNode inspectionConfigTreeNode = q.pullFirst();
                inspectionConfigTreeNode.dropCache();
                final TreeNode parent = inspectionConfigTreeNode.getParent();
                if (parent != null && parent.getParent() != null) {
                  q.addLast((InspectionConfigTreeNode)parent);
                }
              }

              myTreeTable.updateUI();
              if (updateOptionsAndDescriptionPanel) {
                updateOptionsAndDescriptionPanel();
              }
            }
          });

        final ToolbarDecorator wrappedTable = ToolbarDecorator.createDecorator(scopesAndScopesAndSeveritiesTable).disableUpDownActions().setRemoveActionUpdater(
          new AnActionButtonUpdater() {
            @Override
            public boolean isEnabled(AnActionEvent e) {
              final int selectedRow = scopesAndScopesAndSeveritiesTable.getSelectedRow();
              final int rowCount = scopesAndScopesAndSeveritiesTable.getRowCount();
              return rowCount - 1 != selectedRow;
            }
          });
        final JPanel panel = wrappedTable.createPanel();
        panel.setMinimumSize(new Dimension(getMinimumSize().width, 3 * scopesAndScopesAndSeveritiesTable.getRowHeight()));
        severityPanel.add(new JBLabel("Severity by Scope"),
                          new GridBagConstraints(0, 0, 1, 1, 1.0, 0, GridBagConstraints.NORTHWEST, GridBagConstraints.NONE,
                                                 JBUI.insets(5, 0, 2, 10), 0, 0));
        severityPanel.add(panel, new GridBagConstraints(0, 1, 1, 1, 0, 1.0, GridBagConstraints.NORTHWEST, GridBagConstraints.BOTH,
                                                        JBUI.insets(0, 0, 0, 0), 0, 0));
        severityPanelWeightY = 0.3;
      }
      myOptionsPanel.add(severityPanel, new GridBagConstraints(0, 0, 1, 1, 1.0, severityPanelWeightY, GridBagConstraints.WEST, GridBagConstraints.BOTH,
                                                               JBUI.insets(0, 2, 0, 0), 0, 0));
      if (configPanelAnchor.getComponentCount() != 0) {
        configPanelAnchor.setBorder(IdeBorderFactory.createTitledBorder("Options", false, new JBInsets(7, 0, 0, 0)));
      }
      GuiUtils.enableChildren(myOptionsPanel, isThoughOneNodeEnabled(nodes));
      if (configPanelAnchor.getComponentCount() != 0 || scopesNames.isEmpty()) {
        myOptionsPanel.add(configPanelAnchor, new GridBagConstraints(0, 1, 1, 1, 1.0, 1.0, GridBagConstraints.WEST, GridBagConstraints.BOTH,
                                                                     JBUI.insets(0, 2, 0, 0), 0, 0));
      }
      myOptionsPanel.revalidate();
    }
    else {
      initOptionsAndDescriptionPanel();
    }
    myOptionsPanel.repaint();
  }

  private boolean isThoughOneNodeEnabled(final List<InspectionConfigTreeNode> nodes) {
    final Project project = myProjectProfileManager.getProject();
    for (final InspectionConfigTreeNode node : nodes) {
      final String toolId = node.getDefaultDescriptor().getKey().toString();
      if (myProfile.getTools(toolId, project).isEnabled()) {
        return true;
      }
    }
    return false;
  }

  private void updateOptionsAndDescriptionPanel() {
    final TreePath[] paths = myTreeTable.getTree().getSelectionPaths();
    if (paths != null) {
      updateOptionsAndDescriptionPanel(paths);
    } else {
      initOptionsAndDescriptionPanel();
    }
  }

  private void initOptionsAndDescriptionPanel() {
    myOptionsPanel.removeAll();
    readHTML(myBrowser, EMPTY_HTML);
    myOptionsPanel.validate();
    myOptionsPanel.repaint();
  }

  public InspectionProfileModifiableModel getProfile() {
    return myProfile;
  }

  private void setProfile(InspectionProfileModifiableModel modifiableModel) {
    if (myProfile == modifiableModel) {
      return;
    }
    myProfile = modifiableModel;
    initToolStates();
  }

  @Override
  public Dimension getPreferredSize() {
    return new Dimension(700, 500);
  }

  public void disposeUI() {
    if (myInspectionProfilePanel == null) {
      return;
    }
    myAlarm.cancelAllRequests();
    myProfileFilter.dispose();
    if (myProfile != null) {
      for (ScopeToolState state : myProfile.getAllTools(myProjectProfileManager.getProject())) {
        state.resetConfigPanel();
      }
    }
    myProfile = null;
    Disposer.dispose(myDisposable);
    myDisposable = null;
  }

  private JPanel createInspectionProfileSettingsPanel() {

    myBrowser = new JEditorPane(UIUtil.HTML_MIME, EMPTY_HTML);
    myBrowser.setEditable(false);
    myBrowser.setBorder(IdeBorderFactory.createEmptyBorder(5, 5, 5, 5));
    myBrowser.addHyperlinkListener(BrowserHyperlinkListener.INSTANCE);

    initToolStates();
    fillTreeData(myProfileFilter != null ? myProfileFilter.getFilter() : null, true);

    JPanel descriptionPanel = new JPanel(new BorderLayout());
    descriptionPanel.setBorder(IdeBorderFactory.createTitledBorder(InspectionsBundle.message("inspection.description.title"), false,
                                                                   new JBInsets(2, 2, 0, 0)));
    descriptionPanel.add(ScrollPaneFactory.createScrollPane(myBrowser), BorderLayout.CENTER);

    JBSplitter rightSplitter =
      new JBSplitter(true, "SingleInspectionProfilePanel.HORIZONTAL_DIVIDER_PROPORTION", DIVIDER_PROPORTION_DEFAULT);
    rightSplitter.setFirstComponent(descriptionPanel);

    myOptionsPanel = new JPanel(new GridBagLayout());
    initOptionsAndDescriptionPanel();
    rightSplitter.setSecondComponent(myOptionsPanel);
    rightSplitter.setHonorComponentsMinimumSize(true);

    final JScrollPane tree = initTreeScrollPane();

    final JPanel northPanel = new JPanel(new GridBagLayout());
    northPanel.setBorder(IdeBorderFactory.createEmptyBorder(2, 0, 2, 0));
    myProfileFilter.setPreferredSize(new Dimension(20, myProfileFilter.getPreferredSize().height));
    northPanel.add(myProfileFilter, new GridBagConstraints(0, 0, 1, 1, 0.5, 1, GridBagConstraints.BASELINE_TRAILING, GridBagConstraints.HORIZONTAL,
                                                           JBUI.emptyInsets(), 0, 0));
    northPanel.add(createTreeToolbarPanel().getComponent(), new GridBagConstraints(1, 0, 1, 1, 1, 1, GridBagConstraints.BASELINE_LEADING, GridBagConstraints.HORIZONTAL,
                                                                                   JBUI.emptyInsets(), 0, 0));

    JBSplitter mainSplitter = new OnePixelSplitter(false, DIVIDER_PROPORTION_DEFAULT, 0.01f, 0.99f);
    mainSplitter.setSplitterProportionKey("SingleInspectionProfilePanel.VERTICAL_DIVIDER_PROPORTION");
    mainSplitter.setFirstComponent(tree);
    mainSplitter.setSecondComponent(rightSplitter);
    mainSplitter.setHonorComponentsMinimumSize(false);

    final JPanel panel = new JPanel(new BorderLayout());
    panel.add(northPanel, BorderLayout.NORTH);
    panel.add(mainSplitter, BorderLayout.CENTER);
    return panel;
  }

  public boolean isModified() {
    if (myTreeTable == null) return false;
    if (myModified) return true;
    if (myProfile.isChanged()) return true;
    if (myProfile.getSource().isProjectLevel() != myProfile.isProjectLevel()) return true;
    if (!Comparing.strEqual(myProfile.getSource().getName(), myProfile.getName())) return true;
    if (!Comparing.equal(myInitialScopesOrder, myProfile.getScopesOrder())) return true;
    return descriptorsAreChanged();
  }

  public void reset() {
    myModified = false;
    setProfile(myProfile);
    filterTree();
    final String filter = myProfileFilter.getFilter();
    myProfileFilter.reset();
    myProfileFilter.setSelectedItem(filter);
    myProfile.setName(myProfile.getSource().getName());
    myProfile.setProjectLevel(myProfile.getSource().isProjectLevel());
  }

  public void apply() {
    final boolean modified = isModified();
    if (!modified) {
      return;
    }
    InspectionProfileModifiableModel selectedProfile = myProfile;

    BaseInspectionProfileManager profileManager = selectedProfile.isProjectLevel() ? myProjectProfileManager : (BaseInspectionProfileManager)InspectionProfileManager.getInstance();
    InspectionProfileImpl source = selectedProfile.getSource();

    if (source.getProfileManager() != profileManager) {
      source.getProfileManager().deleteProfile(source);
    }

    if (selectedProfile.getProfileManager() != profileManager) {
      copyUsedSeveritiesIfUndefined(selectedProfile, profileManager);
      selectedProfile.setProfileManager(profileManager);
    }

    selectedProfile.commit();
    profileManager.addProfile(source);
    profileManager.fireProfileChanged(source);

    myModified = false;
    myRoot.dropCache();
    initToolStates();
    updateOptionsAndDescriptionPanel();
  }

  private boolean descriptorsAreChanged() {
    for (ToolDescriptors toolDescriptors : myInitialToolDescriptors.values()) {
      Descriptor desc = toolDescriptors.getDefaultDescriptor();
      Project project = myProjectProfileManager.getProject();
      if (myProfile.isToolEnabled(desc.getKey(), null, project) != desc.isEnabled()){
        return true;
      }
      if (myProfile.getErrorLevel(desc.getKey(), desc.getScope(), project) != desc.getLevel()) {
        return true;
      }
      final List<Descriptor> descriptors = toolDescriptors.getNonDefaultDescriptors();
      for (Descriptor descriptor : descriptors) {
        if (myProfile.isToolEnabled(descriptor.getKey(), descriptor.getScope(), project) != descriptor.isEnabled()) {
          return true;
        }
        if (myProfile.getErrorLevel(descriptor.getKey(), descriptor.getScope(), project) != descriptor.getLevel()) {
          return true;
        }
      }

      final List<ScopeToolState> tools = myProfile.getNonDefaultTools(desc.getKey().toString(), project);
      if (tools.size() != descriptors.size()) {
        return true;
      }
      for (int i = 0; i < tools.size(); i++) {
        final ScopeToolState pair = tools.get(i);
        if (!Comparing.equal(pair.getScope(project), descriptors.get(i).getScope())) {
          return true;
        }
      }
    }


    return false;
  }

  @Override
  public void setVisible(boolean aFlag) {
    if (aFlag && myInspectionProfilePanel == null) {
      initUI();
    }
    super.setVisible(aFlag);
  }

  private void setNewHighlightingLevel(@NotNull HighlightDisplayLevel level) {
    final int[] rows = myTreeTable.getTree().getSelectionRows();
    final boolean showOptionsAndDescriptorPanels = rows != null && rows.length == 1;
    for (int i = 0; rows != null && i < rows.length; i++) {
      final InspectionConfigTreeNode node = (InspectionConfigTreeNode)myTreeTable.getTree().getPathForRow(rows[i]).getLastPathComponent();
      final InspectionConfigTreeNode parent = (InspectionConfigTreeNode)node.getParent();
      final Object userObject = node.getUserObject();
      if (userObject instanceof ToolDescriptors && (node.getScopeName() != null || node.isLeaf())) {
        updateErrorLevel(node, showOptionsAndDescriptorPanels, level);
        updateUpHierarchy(parent);
      }
      else {
        updateErrorLevelUpInHierarchy(level, showOptionsAndDescriptorPanels, node);
        updateUpHierarchy(parent);
      }
    }
    if (rows != null) {
      updateOptionsAndDescriptionPanel(myTreeTable.getTree().getSelectionPaths());
    }
    else {
      initOptionsAndDescriptionPanel();
    }
    repaintTableData();
  }

  private void updateErrorLevelUpInHierarchy(@NotNull HighlightDisplayLevel level,
                                             boolean showOptionsAndDescriptorPanels,
                                             InspectionConfigTreeNode node) {
    node.dropCache();
    for (int j = 0; j < node.getChildCount(); j++) {
      final InspectionConfigTreeNode child = (InspectionConfigTreeNode)node.getChildAt(j);
      final Object userObject = child.getUserObject();
      if (userObject instanceof ToolDescriptors && (child.getScopeName() != null || child.isLeaf())) {
        updateErrorLevel(child, showOptionsAndDescriptorPanels, level);
      }
      else {
        updateErrorLevelUpInHierarchy(level, showOptionsAndDescriptorPanels, child);
      }
    }
  }

  private void updateErrorLevel(final InspectionConfigTreeNode child,
                                final boolean showOptionsAndDescriptorPanels,
                                @NotNull HighlightDisplayLevel level) {
    final HighlightDisplayKey key = child.getDefaultDescriptor().getKey();
    myProfile.setErrorLevel(key, level, null, myProjectProfileManager.getProject());
    child.dropCache();
    if (showOptionsAndDescriptorPanels) {
      updateOptionsAndDescriptionPanel(new TreePath(child.getPath()));
    }
  }

  public JComponent getPreferredFocusedComponent() {
    return myTreeTable;
  }

  private class MyFilterComponent extends FilterComponent {
    private MyFilterComponent() {
      super(INSPECTION_FILTER_HISTORY, 10);
    }

    @Override
    public void filter() {
      filterTree(getFilter());
    }

    @Override
    protected void onlineFilter() {
      if (myProfile == null) return;
      final String filter = getFilter();
      getExpandedNodes(myProfile).saveVisibleState(myTreeTable.getTree());
      fillTreeData(filter, true);
      reloadModel();
      if (filter == null || filter.isEmpty()) {
        restoreTreeState();
      } else {
        TreeUtil.expandAll(myTreeTable.getTree());
      }
    }
  }
}
