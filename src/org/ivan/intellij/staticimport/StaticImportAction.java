package org.ivan.intellij.staticimport;

import com.intellij.codeInsight.FileModificationService;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorAction;
import com.intellij.openapi.editor.actionSystem.EditorWriteActionHandler;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Key;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.tree.java.PsiReferenceExpressionImpl;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Created by tanya on 08.09.15.
 */
public class StaticImportAction extends EditorAction {

    private static final Logger LOG = Logger.getInstance("#org.ivan.intellij.staticimport.StaticImportAction");
    private static final Key<PsiElement> TEMP_REFERENT_USER_DATA = new Key<PsiElement>("TEMP_REFERENT_USER_DATA");

    private static class StaticImportHandler extends EditorWriteActionHandler {
        private StaticImportHandler() {
        }

        @Override
        public void executeWriteAction(Editor editor, Caret caret, DataContext dataContext) {
            if (dataContext == null) {
                return;
            }
            if (editor == null) {
                return;
            }
            PsiFile file = CommonDataKeys.PSI_FILE.getData(dataContext);
            if (file == null) {
                return;
            }
            PsiElement element = getElementIfApplicable(editor, file);
            if (element == null) {
                return;
            }
            invoke(file, element);
        }

        @Nullable
        private static PsiElement getElementIfApplicable(@NotNull Editor editor, @NotNull PsiFile file) {
            PsiElement elementToTheRight = getElementToTheRight(editor, file);
            if (elementToTheRight != null && isApplicable(elementToTheRight)) {
                return elementToTheRight;
            }

            PsiElement elementToTheLeft = getElementToTheLeft(editor, file);
            if (elementToTheLeft != null && isApplicable(elementToTheLeft)) {
                return elementToTheLeft;
            }
            return null;
        }

        @Nullable
        private static PsiElement getElementToTheRight(@NotNull Editor editor, @NotNull PsiFile file) {
            return file.findElementAt(editor.getCaretModel().getOffset());
        }

        @Nullable
        private static PsiElement getElementToTheLeft(@NotNull Editor editor, @NotNull PsiFile file) {
            return file.findElementAt(editor.getCaretModel().getOffset() - 1);
        }

        private static boolean isApplicable(@NotNull PsiElement element) {
            ImportAvailability availability = getStaticImportClass(element);
            return availability != null
                    && (availability.resolved instanceof PsiClass || element.getContainingFile() instanceof PsiJavaFile);
        }
    }

    public StaticImportAction() {
        super(new StaticImportHandler());
    }

    public static class ImportAvailability {
        private final String qName;
        private final PsiMember resolved;

        private ImportAvailability(String qName, PsiMember resolved) {
            this.qName = qName;
            this.resolved = resolved;
        }
    }

    /**
     * Allows to check if it's possible to perform static import for the target element.
     *
     * @param element     target element that is static import candidate
     * @return            not-null qualified name of the class which method may be statically imported if any; <code>null</code> otherwise
     */
    @Nullable
    public static ImportAvailability getStaticImportClass(@NotNull PsiElement element) {
        if (!PsiUtil.isLanguageLevel5OrHigher(element)) return null;
        if (element instanceof PsiIdentifier) {
            final PsiElement parent = element.getParent();
            if (parent instanceof PsiMethodReferenceExpression) return null;
            if (parent instanceof PsiJavaCodeReferenceElement && ((PsiJavaCodeReferenceElement)parent).getQualifier() != null) {
                if (PsiTreeUtil.getParentOfType(parent, PsiImportList.class) != null) return null;
                PsiJavaCodeReferenceElement refExpr = (PsiJavaCodeReferenceElement)parent;
                if (checkParameterizedReference(refExpr)) return null;
                JavaResolveResult[] results = refExpr.multiResolve(false);
                for (JavaResolveResult result : results) {
                    final PsiElement resolved = result.getElement();
                    if (resolved instanceof PsiMember && ((PsiModifierListOwner)resolved).hasModifierProperty(PsiModifier.STATIC)) {
                        PsiClass aClass = getResolvedClass(element, (PsiMember)resolved);
                        if (aClass != null && !PsiTreeUtil.isAncestor(aClass, element, true) && !aClass.hasModifierProperty(PsiModifier.PRIVATE)) {
                            final PsiElement gParent = refExpr.getParent();
                            if (gParent instanceof PsiMethodCallExpression) {
                                final PsiMethodCallExpression call = (PsiMethodCallExpression)gParent.copy();
                                final PsiElement qualifier = call.getMethodExpression().getQualifier();
                                if (qualifier == null) return null;
                                qualifier.delete();
                                final PsiMethod method = call.resolveMethod();
                                if (method != null && method.getContainingClass() != aClass)  return null;
                            }
                            else {
                                final PsiJavaCodeReferenceElement copy = (PsiJavaCodeReferenceElement)refExpr.copy();
                                final PsiElement qualifier = copy.getQualifier();
                                if (qualifier == null) return null;
                                qualifier.delete();
                                final PsiElement target = copy.resolve();
                                if (target != null && PsiTreeUtil.getParentOfType(target, PsiClass.class) != aClass) return null;
                            }
                            String qName = aClass.getQualifiedName();
                            if (qName != null && !Comparing.strEqual(qName, aClass.getName())) {
                                return new ImportAvailability(qName + "." +refExpr.getReferenceName(), (PsiMember) resolved);
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    private static PsiImportStatementBase findExistingImport(PsiFile file, PsiClass aClass, String refName) {
        if (file instanceof PsiJavaFile) {
            PsiImportList importList = ((PsiJavaFile)file).getImportList();
            if (importList != null) {
                for (PsiImportStaticStatement staticStatement : importList.getImportStaticStatements()) {
                    if (staticStatement.isOnDemand()) {
                        if (staticStatement.resolveTargetClass() == aClass) {
                            return staticStatement;
                        }
                    }
                }

                final PsiImportStatementBase importStatement = importList.findSingleImportStatement(refName);
                if (importStatement instanceof PsiImportStaticStatement &&
                        ((PsiImportStaticStatement)importStatement).resolveTargetClass() == aClass) {
                    return importStatement;
                }
            }
        }
        return null;
    }

    private static boolean checkParameterizedReference(PsiJavaCodeReferenceElement refExpr) {
        PsiReferenceParameterList parameterList = refExpr instanceof PsiReferenceExpression ? refExpr.getParameterList() : null;
        return parameterList != null && parameterList.getFirstChild() != null;
    }

    @Nullable
    private static PsiClass getResolvedClass(PsiElement element, PsiMember resolved) {
        PsiClass aClass = resolved.getContainingClass();
        if (aClass != null && !PsiUtil.isAccessible(aClass.getProject(), aClass, element, null)) {
            final PsiElement qualifier = ((PsiJavaCodeReferenceElement)element.getParent()).getQualifier();
            if (qualifier instanceof PsiReferenceExpression) {
                final PsiElement qResolved = ((PsiReferenceExpression)qualifier).resolve();
                if (qResolved instanceof PsiVariable) {
                    aClass = PsiUtil.resolveClassInClassTypeOnly(((PsiVariable)qResolved).getType());
                }
            }
        }
        return aClass;
    }

    public static void invoke(PsiFile file, final PsiElement element) {
        if (!FileModificationService.getInstance().prepareFileForWrite(file)) return;
        final PsiJavaCodeReferenceElement refExpr = (PsiJavaCodeReferenceElement)element.getParent();
        final String referenceName = refExpr.getReferenceName();
        final JavaResolveResult[] targets = refExpr.multiResolve(false);
        for (JavaResolveResult target : targets) {
            final PsiElement resolved = target.getElement();
            if (resolved != null) {
                bindAllClassRefs(file, resolved, referenceName, getResolvedClass(element, (PsiMember)resolved));
                return;
            }
        }
    }

    public static void bindAllClassRefs(final PsiFile file,
                                        final PsiElement resolved,
                                        final String referenceName,
                                        final PsiClass resolvedClass) {
        file.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitReferenceElement(PsiJavaCodeReferenceElement reference) {
                super.visitReferenceElement(reference);

                if (referenceName != null && referenceName.equals(reference.getReferenceName())) {
                    PsiElement resolved = reference.resolve();
                    if (resolved != null) {
                        reference.putUserData(TEMP_REFERENT_USER_DATA, resolved);
                    }
                }
            }
        });

        if (resolved != null && findExistingImport(file, resolvedClass, referenceName) == null) {
            if (resolved instanceof PsiClass) {
                ((PsiImportHolder) file).importClass((PsiClass) resolved);
            } else {
                PsiReferenceExpressionImpl.bindToElementViaStaticImport(resolvedClass, referenceName, ((PsiJavaFile) file).getImportList());
            }
        }

        file.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitImportList(PsiImportList list) {
            }

            @Override
            public void visitReferenceElement(PsiJavaCodeReferenceElement reference) {

                try {
                    if (checkParameterizedReference(reference)) return;

                    if (referenceName.equals(reference.getReferenceName()) && !(reference instanceof PsiMethodReferenceExpression)) {
                        final PsiElement qualifierExpression = reference.getQualifier();
                        PsiElement referent = reference.getUserData(TEMP_REFERENT_USER_DATA);
                        if (!reference.isQualified()) {
                            if (referent instanceof PsiMember && referent != reference.resolve()) {
                                PsiElementFactory factory = JavaPsiFacade.getInstance(reference.getProject()).getElementFactory();
                                try {
                                    final PsiClass containingClass = ((PsiMember)referent).getContainingClass();
                                    if (containingClass != null) {
                                        PsiReferenceExpression copy = (PsiReferenceExpression)factory.createExpressionFromText("A." + reference.getReferenceName(), null);
                                        reference = (PsiReferenceExpression)reference.replace(copy);
                                        ((PsiReferenceExpression)reference.getQualifier()).bindToElement(containingClass);
                                    }
                                }
                                catch (IncorrectOperationException e) {
                                    LOG.error (e);
                                }
                            }
                            reference.putUserData(TEMP_REFERENT_USER_DATA, null);
                        } else {
                            if (qualifierExpression instanceof PsiJavaCodeReferenceElement) {
                                PsiElement aClass = ((PsiJavaCodeReferenceElement)qualifierExpression).resolve();
                                if (aClass instanceof PsiVariable) {
                                    aClass = PsiUtil.resolveClassInClassTypeOnly(((PsiVariable)aClass).getType());
                                }
                                if (aClass instanceof PsiClass && InheritanceUtil.isInheritorOrSelf((PsiClass) aClass, resolvedClass, true)) {
                                    boolean foundMemberByName = false;
                                    if (referent instanceof PsiMember) {
                                        final String memberName = ((PsiMember)referent).getName();
                                        final PsiClass containingClass = PsiTreeUtil.getParentOfType(reference, PsiClass.class);
                                        if (containingClass != null) {
                                            foundMemberByName |= containingClass.findFieldByName(memberName, true) != null;
                                            foundMemberByName |= containingClass.findMethodsByName(memberName, true).length > 0;
                                        }
                                    }
                                    if (!foundMemberByName) {
                                        try {
                                            qualifierExpression.delete();
                                        }
                                        catch (IncorrectOperationException e) {
                                            LOG.error(e);
                                        }
                                    }
                                }
                            }
                        }
                        reference.putUserData(TEMP_REFERENT_USER_DATA, null);
                    }
                }
                finally {
                    super.visitReferenceElement(reference);
                }
            }
        });
    }
}
