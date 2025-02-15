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

package com.intellij.codeInspection;

import com.intellij.codeHighlighting.HighlightDisplayLevel;
import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.scope.packageSet.NamedScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * User: anna
 * Date: 15-Feb-2006
 */
public interface ModifiableModel extends InspectionProfile {
  void enableTool(@NotNull String inspectionTool, NamedScope namedScope, Project project);

  void setErrorLevel(HighlightDisplayKey key, @NotNull HighlightDisplayLevel level, Project project);

  @Override
  HighlightDisplayLevel getErrorLevel(HighlightDisplayKey inspectionToolKey, PsiElement element);

  @Override
  boolean isToolEnabled(HighlightDisplayKey key);

  @Override
  boolean isToolEnabled(@Nullable HighlightDisplayKey key, @Nullable PsiElement element);

  @Override
  InspectionProfileEntry getUnwrappedTool(@NotNull String shortName, @NotNull PsiElement element);

  @Override
  InspectionToolWrapper[] getInspectionTools(PsiElement element);

  /**
   * @see InspectionProfile#getSingleTool()
   */
  void setSingleTool(@NotNull String toolShortName);

  void disableTool(@NotNull String toolId, @NotNull PsiElement element);

  void disableTool(@NotNull String inspectionTool, @Nullable Project project);
}
