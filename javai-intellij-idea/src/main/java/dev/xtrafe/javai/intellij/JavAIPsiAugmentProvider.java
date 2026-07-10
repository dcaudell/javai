package dev.xtrafe.javai.intellij;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.augment.PsiAugmentProvider;
import com.intellij.psi.impl.light.LightMethodBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Teaches IntelliJ's static analysis about the members {@code javai-substrate}'s ByteBuddy weaver adds to a
 * {@code @JavAIVectorizable}-annotated class at class-load time -- the {@code JavAIVectorizable}/{@code
 * JavAIDirtyTracking} contract methods, plus a per-{@code @Vectorize}-field {@code <field>Vector()}
 * accessor. Purely an IDE-analysis concern: {@link #getAugments} synthesizes lightweight {@link PsiMethod}
 * stand-ins that make autocomplete/"cannot resolve method" happy; it never touches, calls, or depends on
 * the real weaver, and has zero effect on how a project actually compiles or runs.
 *
 * <p>Matches by annotation qualified name (string comparison, not a real dependency on
 * {@code javai-annotations}) deliberately: this plugin has no compile-time dependency on any javai module,
 * keeping it buildable and distributable entirely independently of the reactor it supports. See
 * doc/spec/intellij-plugin.md in the javai repository for the full design and what's deliberately not
 * covered yet (constructor-synthesized dependency wiring, {@code @SearchVisibility}/{@code
 * @JavAIGraphNode}-specific members, and anything from {@code javai-supervision}, which never adds new
 * callable methods in the first place -- see that doc for why).
 */
public final class JavAIPsiAugmentProvider extends PsiAugmentProvider {

    private static final String JAVAI_VECTORIZABLE_ANNOTATION = "dev.xtrafe.javai.annotations.JavAIVectorizable";
    private static final String VECTORIZE_ANNOTATION = "dev.xtrafe.javai.annotations.Vectorize";
    private static final String VECTORIZE_IGNORE_ANNOTATION = "dev.xtrafe.javai.annotations.VectorizeIgnore";

    private static final String EMBEDDING_VECTOR = "dev.xtrafe.javai.vector.EmbeddingVector";
    private static final String JAVAI_VECTORIZABLE_TYPE = "dev.xtrafe.javai.model.JavAIVectorizable";
    private static final String JAVAI_LIST = "dev.xtrafe.javai.model.JavAIList";

    @Override
    protected @NotNull <Psi extends PsiElement> List<Psi> getAugments(
            @NotNull PsiElement element, @NotNull Class<Psi> type, @Nullable String nameHint) {
        if (type != PsiMethod.class || !(element instanceof PsiClass psiClass)) {
            return List.of();
        }
        if (AnnotationUtil.findAnnotation(psiClass, JAVAI_VECTORIZABLE_ANNOTATION) == null) {
            return List.of();
        }

        PsiElementFactory factory = JavaPsiFacade.getElementFactory(psiClass.getProject());
        List<PsiMethod> methods = new ArrayList<>();

        // JavAIVectorizable's contract.
        methods.add(method(psiClass, factory, "vector", EMBEDDING_VECTOR));
        methods.add(method(psiClass, factory, "fieldVector", EMBEDDING_VECTOR, "java.lang.String"));
        methods.add(method(psiClass, factory, "summaryVector", EMBEDDING_VECTOR));
        methods.add(method(psiClass, factory, "similarityTo", "double", JAVAI_VECTORIZABLE_TYPE));
        methods.add(method(psiClass, factory, "similarityTo", "double", EMBEDDING_VECTOR));
        // Raw JavAIList, not JavAIList<T> -- LightMethodBuilder's parameter-type API makes a properly
        // parameterized generic return type more trouble than it's worth here; the practical effect is an
        // "unchecked conversion" warning at the call site instead of a hard "cannot resolve method" error,
        // an acceptable trade for how much simpler this stays.
        methods.add(method(psiClass, factory, "query", JAVAI_LIST, EMBEDDING_VECTOR, "java.lang.Class"));
        methods.add(method(psiClass, factory, "query", JAVAI_LIST, EMBEDDING_VECTOR, "java.lang.Class", "int"));

        // JavAIDirtyTracking's contract.
        methods.add(method(psiClass, factory, "markFieldDirty", "void"));
        methods.add(method(psiClass, factory, "isFieldDirty", "boolean"));
        methods.add(method(psiClass, factory, "clearFieldDirty", "void"));
        methods.add(method(psiClass, factory, "markSummaryDirty", "void"));
        methods.add(method(psiClass, factory, "isSummaryDirty", "boolean"));
        methods.add(method(psiClass, factory, "clearSummaryDirty", "void"));
        methods.add(method(psiClass, factory, "addDependent", "void", "java.lang.Object"));
        methods.add(method(psiClass, factory, "dependents", "java.lang.Iterable"));

        for (String fieldName : vectorizeFieldNames(psiClass)) {
            methods.add(method(psiClass, factory, fieldName + "Vector", EMBEDDING_VECTOR));
        }

        @SuppressWarnings("unchecked")
        List<Psi> result = (List<Psi>) (List<?>) methods;
        return result;
    }

    /**
     * {@code @Vectorize} fields minus {@code @VectorizeIgnore} ones, walking the full class hierarchy via
     * {@link PsiClass#getAllFields()} -- mirrors {@code JavAIWeaver}'s own support for {@code @Vectorize}
     * fields declared on a plain (non-{@code @JavAIVectorizable}) superclass.
     */
    private static Set<String> vectorizeFieldNames(PsiClass psiClass) {
        Set<String> included = new LinkedHashSet<>();
        Set<String> ignored = new LinkedHashSet<>();
        for (PsiField field : psiClass.getAllFields()) {
            if (AnnotationUtil.findAnnotation(field, VECTORIZE_ANNOTATION) != null) {
                included.add(field.getName());
            }
            if (AnnotationUtil.findAnnotation(field, VECTORIZE_IGNORE_ANNOTATION) != null) {
                ignored.add(field.getName());
            }
        }
        included.removeAll(ignored);
        return included;
    }

    private static PsiMethod method(
            PsiClass containingClass, PsiElementFactory factory, String name, String returnTypeText, String... paramTypeTexts) {
        LightMethodBuilder builder = new LightMethodBuilder(containingClass.getManager(), name);
        builder.setContainingClass(containingClass);
        builder.setModifiers(PsiModifier.PUBLIC);
        builder.setMethodReturnType(type(factory, containingClass, returnTypeText));
        for (int i = 0; i < paramTypeTexts.length; i++) {
            builder.addParameter("arg" + i, type(factory, containingClass, paramTypeTexts[i]));
        }
        builder.setNavigationElement(containingClass);
        return builder;
    }

    private static PsiType type(PsiElementFactory factory, PsiClass context, String typeText) {
        return switch (typeText) {
            case "void" -> PsiTypes.voidType();
            case "boolean" -> PsiTypes.booleanType();
            case "int" -> PsiTypes.intType();
            case "double" -> PsiTypes.doubleType();
            default -> factory.createTypeFromText(typeText, context);
        };
    }
}
