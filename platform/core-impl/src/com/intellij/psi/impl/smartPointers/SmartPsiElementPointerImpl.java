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

package com.intellij.psi.impl.smartPointers;

import com.intellij.lang.LanguageUtil;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.*;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.FreeThreadedFileViewProvider;
import com.intellij.psi.impl.PsiManagerEx;
import com.intellij.psi.impl.source.PsiFileImpl;
import com.intellij.psi.impl.source.PsiFileWithStubSupport;
import com.intellij.psi.impl.source.tree.ForeignLeafPsiElement;
import com.intellij.psi.stubs.IStubElementType;
import com.intellij.psi.stubs.StubTree;
import com.intellij.psi.tree.IStubFileElementType;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;

class SmartPsiElementPointerImpl<E extends PsiElement> implements SmartPointerEx<E> {
  private static final Logger LOG = Logger.getInstance("#com.intellij.psi.impl.smartPointers.SmartPsiElementPointerImpl");

  private Reference<E> myElement;
  private final SmartPointerElementInfo myElementInfo;
  private final Class<? extends PsiElement> myElementClass;
  private byte myReferenceCount = 1;
  @Nullable SmartPointerTracker.PointerReference pointerReference;

  SmartPsiElementPointerImpl(@NotNull Project project, @NotNull E element, @Nullable PsiFile containingFile, boolean forInjected) {
    this(element, createElementInfo(project, element, containingFile, forInjected), element.getClass());
  }
  SmartPsiElementPointerImpl(@NotNull E element,
                             @NotNull SmartPointerElementInfo elementInfo,
                             @NotNull Class<? extends PsiElement> elementClass) {
    ApplicationManager.getApplication().assertReadAccessAllowed();
    myElementClass = elementClass;
    myElementInfo = elementInfo;
    cacheElement(element);
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof SmartPsiElementPointer && pointsToTheSameElementAs(this, (SmartPsiElementPointer)obj);
  }

  @Override
  public int hashCode() {
    return myElementInfo.elementHashCode();
  }

  @Override
  @NotNull
  public Project getProject() {
    return myElementInfo.getProject();
  }

  @Override
  @Nullable
  public E getElement() {
    E element = getCachedElement();
    if (element == null || !element.isValid()) {
      element = doRestoreElement();
      cacheElement(element);
    }
    return element;
  }

  @Nullable
  E doRestoreElement() {
    //noinspection unchecked
    E element = (E)myElementInfo.restoreElement();
    if (element != null && (!element.getClass().equals(myElementClass) || !element.isValid())) {
      return null;
    }
    return element;
  }

  void cacheElement(@Nullable E element) {
    myElement = element == null ? null : 
                PsiManagerEx.getInstanceEx(getProject()).isBatchFilesProcessingMode() ? new WeakReference<E>(element) :
                new SoftReference<E>(element);
  }

  @Override
  public E getCachedElement() {
    return com.intellij.reference.SoftReference.dereference(myElement);
  }

  @Override
  public PsiFile getContainingFile() {
    PsiFile file = getElementInfo().restoreFile();

    if (file != null) {
      return file;
    }

    final Document doc = myElementInfo.getDocumentToSynchronize();
    if (doc == null) {
      final E resolved = getElement();
      return resolved == null ? null : resolved.getContainingFile();
    }
    return PsiDocumentManager.getInstance(getProject()).getPsiFile(doc);
  }

  @Override
  public VirtualFile getVirtualFile() {
    return myElementInfo.getVirtualFile();
  }

  @Override
  public Segment getRange() {
    return myElementInfo.getRange();
  }

  @Nullable
  @Override
  public Segment getPsiRange() {
    return myElementInfo.getPsiRange();
  }

  @NotNull
  private static <E extends PsiElement> SmartPointerElementInfo createElementInfo(@NotNull Project project,
                                                                                  @NotNull E element,
                                                                                  PsiFile containingFile, boolean forInjected) {
    SmartPointerElementInfo elementInfo = doCreateElementInfo(project, element, containingFile, forInjected);
    if (ApplicationManager.getApplication().isUnitTestMode() && !element.equals(elementInfo.restoreElement())) {
      // likely cause: PSI having isPhysical==true, but which can't be restored by containing file and range. To fix, make isPhysical return false
      LOG.error("Cannot restore " + element + " of " + element.getClass() + " from " + elementInfo);
    }
    return elementInfo;
  }

  @NotNull
  private static <E extends PsiElement> SmartPointerElementInfo doCreateElementInfo(@NotNull Project project,
                                                                                    @NotNull E element,
                                                                                    PsiFile containingFile, boolean forInjected) {
    if (element instanceof PsiDirectory) {
      return new DirElementInfo((PsiDirectory)element);
    }
    if (element instanceof PsiCompiledElement || containingFile == null || !containingFile.isPhysical() || !element.isPhysical()) {
      if (element instanceof StubBasedPsiElement && element instanceof PsiCompiledElement) {
        if (element instanceof PsiFile) {
          return new FileElementInfo((PsiFile)element);
        }
        PsiAnchor.StubIndexReference stubReference = PsiAnchor.createStubReference(element, containingFile);
        if (stubReference != null) {
          return new ClsElementInfo(stubReference);
        }
      }
      return new HardElementInfo(project, element);
    }

    FileViewProvider viewProvider = containingFile.getViewProvider();
    if (viewProvider instanceof FreeThreadedFileViewProvider) {
      PsiLanguageInjectionHost hostContext = InjectedLanguageManager.getInstance(containingFile.getProject()).getInjectionHost(containingFile);
      TextRange elementRange = element.getTextRange();
      if (hostContext != null && elementRange != null) {
        SmartPsiElementPointer<PsiLanguageInjectionHost> hostPointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(hostContext);
        return new InjectedSelfElementInfo(project, element, elementRange, containingFile, hostPointer);
      }
    }

    SmartPointerElementInfo info = createAnchorInfo(element, containingFile);
    if (info != null) {
      return info;
    }

    if (element instanceof PsiFile) {
      return new FileElementInfo((PsiFile)element);
    }

    TextRange elementRange = element.getTextRange();
    if (elementRange == null) {
      return new HardElementInfo(project, element);
    }
    if (elementRange.isEmpty() && PsiTreeUtil.findChildOfType(element, ForeignLeafPsiElement.class) != null) {
      // PSI built on C-style macro expansions. It has empty ranges, no text, but complicated structure. It can't be reliably
      // restored by just one offset in a file, so hold it on a hard reference
      return new HardElementInfo(project, element);
    }
    ProperTextRange proper = ProperTextRange.create(elementRange);

    return new SelfElementInfo(project, proper, Identikit.fromPsi(element, LanguageUtil.getRootLanguage(element)), containingFile, forInjected);
  }

  @Nullable
  private static SmartPointerElementInfo createAnchorInfo(@NotNull PsiElement element, @NotNull PsiFile containingFile) {
    if (element instanceof StubBasedPsiElement && containingFile instanceof PsiFileWithStubSupport) {
      PsiFileWithStubSupport stubFile = (PsiFileWithStubSupport)containingFile;
      StubTree stubTree = stubFile.getStubTree();
      if (stubTree != null) {
        // use stubs when tree is not loaded
        StubBasedPsiElement stubPsi = (StubBasedPsiElement)element;
        int stubId = PsiAnchor.calcStubIndex(stubPsi);
        IStubElementType myStubElementType = stubPsi.getElementType();

        IStubFileElementType elementTypeForStubBuilder = ((PsiFileImpl)containingFile).getElementTypeForStubBuilder();
        if (stubId != -1 && elementTypeForStubBuilder != null) { // TemplateDataElementType is not IStubFileElementType
          return new AnchorElementInfo(element, stubFile, stubId, myStubElementType);
        }
      }
    }

    Pair<Identikit.ByAnchor, PsiElement> pair = Identikit.withAnchor(element, LanguageUtil.getRootLanguage(containingFile));
    if (pair != null) {
      return new AnchorElementInfo(pair.second, containingFile, pair.first);
    }
    return null;
  }

  @NotNull
  SmartPointerElementInfo getElementInfo() {
    return myElementInfo;
  }

  static boolean pointsToTheSameElementAs(@NotNull SmartPsiElementPointer pointer1, @NotNull SmartPsiElementPointer pointer2) {
    if (pointer1 == pointer2) return true;
    if (pointer1 instanceof SmartPsiElementPointerImpl && pointer2 instanceof SmartPsiElementPointerImpl) {
      SmartPsiElementPointerImpl impl1 = (SmartPsiElementPointerImpl)pointer1;
      SmartPsiElementPointerImpl impl2 = (SmartPsiElementPointerImpl)pointer2;
      SmartPointerElementInfo elementInfo1 = impl1.getElementInfo();
      SmartPointerElementInfo elementInfo2 = impl2.getElementInfo();
      if (!elementInfo1.pointsToTheSameElementAs(elementInfo2)) return false;
      PsiElement cachedElement1 = impl1.getCachedElement();
      PsiElement cachedElement2 = impl2.getCachedElement();
      return cachedElement1 == null || cachedElement2 == null || Comparing.equal(cachedElement1, cachedElement2);
    }
    return Comparing.equal(pointer1.getElement(), pointer2.getElement());
  }

  synchronized int incrementAndGetReferenceCount(int delta) {
    if (myReferenceCount == Byte.MAX_VALUE) return Byte.MAX_VALUE; // saturated
    if (myReferenceCount == 0) return 0; // disposed, not to be reused again
    return myReferenceCount += delta;
  }

  @Override
  public String toString() {
    return myElementInfo.toString();
  }
}
