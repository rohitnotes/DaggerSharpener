/*
 * Copyright 2018 Alexandru Iustin Dochioiu
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
package com.github.alexdochioiu.daggersharpenerprocessor;

import com.github.alexdochioiu.daggersharpenerprocessor.models.SharpComponentModel;
import com.github.alexdochioiu.daggersharpenerprocessor.utils.SharpComponentUtils;
import com.github.alexdochioiu.daggersharpenerprocessor.utils.SharpenerAnnotationUtils;
import com.github.alexdochioiu.daggersharpenerprocessor.utils.dagger2.AnnotationUtils;
import com.github.alexdochioiu.daggersharpenerprocessor.utils.dagger2.ScopeUtils;
import com.github.alexdochioiu.daggersharpenerprocessor.utils.java.AnnotationValueUtils;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

/**
 * Created by Alexandru Iustin Dochioiu on 7/26/2018
 */

@SupportedSourceVersion(SourceVersion.RELEASE_7)
public class SharpProcessor extends AbstractProcessor {

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> supportedAnnotation = new LinkedHashSet<>();
        supportedAnnotation.add("com.github.alexdochioiu.daggersharpener.SharpComponent");
        supportedAnnotation.add("com.github.alexdochioiu.daggersharpener.SharpScope");
        supportedAnnotation.add("com.github.alexdochioiu.daggersharpener.NoScope");

        return supportedAnnotation;
    }

    private ProcessingEnvironment processingEnvironment;
    private boolean initFinishedSuccessfully = true;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);

        this.processingEnvironment = processingEnvironment;
        MessagerWrapper.initInstance(processingEnvironment.getMessager());

        initFinishedSuccessfully &= AnnotationUtils.init(processingEnvironment);
        initFinishedSuccessfully &= ScopeUtils.init(processingEnvironment);
        initFinishedSuccessfully &= SharpenerAnnotationUtils.init(processingEnvironment);
    }

    @Override
    public boolean process(Set<? extends TypeElement> set, RoundEnvironment roundEnvironment) {
        if (!initFinishedSuccessfully) {
            // TODO message
            return false;
        }

        for (SharpComponentModel model : getComponents(roundEnvironment)) {
            createDaggerComponent(model);
        }

        return true;
    }

    private void createDaggerComponent(SharpComponentModel model) {
        TypeSpec.Builder generatedComponentBuilder = TypeSpec.interfaceBuilder(model.getComponentName())
                .addAnnotation(AnnotationUtils.getDaggerComponentAnnotation(
                        processingEnvironment,
                        model.getComponentDependencies(),
                        model.getComponentSharpDependencies(),
                        model.getComponentModules()))
                .addModifiers(Modifier.PUBLIC);

        if (model.scope.isSharpScoped()) {
            createDaggerSharpScope(model);

            generatedComponentBuilder.addAnnotation(ClassName.get(model.packageString, model.getSharpScopeName()));
        } else {
            ClassName className = (ClassName) ClassName.get(model.scope.getScopeTypeMirror());
            generatedComponentBuilder.addAnnotation(className);
        }

        final ParameterSpec param = ParameterSpec.builder(model.annotatedClass, "thisClass").build();

        generatedComponentBuilder.addMethod(
                MethodSpec.methodBuilder("inject")
                        .returns(model.annotatedClass)
                        .addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC)
                        .addParameter(param)
                        .build()
        );

        for (AnnotationValue providedClass : model.getComponentProvides()) {
            generatedComponentBuilder.addMethod(
                    MethodSpec.methodBuilder(String.format("provide%s", AnnotationValueUtils.getSimpleName(providedClass)))
                            .returns(TypeName.get(processingEnvironment.getElementUtils().getTypeElement(AnnotationValueUtils.getFullName(providedClass)).asType()))
                            .addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC)
                            .build()
            );
        }

        try {
            JavaFile.builder(model.packageString, generatedComponentBuilder.build())
                    .addFileComment("Generated by DaggerSharpener")
                    .build()
                    .writeTo(processingEnvironment.getFiler());
        } catch (IOException e) {
            e.printStackTrace();
            MessagerWrapper.logError(String.format("Could not generate component '%s'", model.getComponentName()));
        }
    }

    private List<SharpComponentModel> getComponents(RoundEnvironment roundEnvironment) {
        final List<SharpComponentModel> componentModels = new ArrayList<>(); //TODO consider switching to linked list

        // get all the classes annotated as SharpComponent
        final Set<? extends Element> sharpClasses = roundEnvironment.getElementsAnnotatedWith(SharpenerAnnotationUtils.getSharpComponentAnnotation());

        // if there's no classes annotated, return our empty list
        if (sharpClasses == null || sharpClasses.isEmpty()) {
            return componentModels;
        }

        for (final Element element : sharpClasses) {
            if (element.getKind() != ElementKind.CLASS) {
                MessagerWrapper.logError("SharpComponent annotation applies only to classes! Cannot be used with '%s'", element.getSimpleName());
                return componentModels;
            }

            final SharpComponentModel model = SharpComponentUtils.getSharpComponentModel(element, processingEnvironment);

            componentModels.add(model);
        }

        return componentModels;
    }

    /**
     * <b>NOTE:</b> this assumes we already checked and a scope should be created for this component.
     *
     * @param model the {@link SharpComponentModel} requiring a scope
     */
    private void createDaggerSharpScope(SharpComponentModel model) {
        AnnotationSpec runtimeRetention = AnnotationSpec.builder(ClassName.get(ScopeUtils.getRetentionElement()))
                .addMember("value", CodeBlock.builder().add("$T.RUNTIME", ScopeUtils.getRetentionPolicyElement()).build())
                .build();

        TypeSpec generatedScopeBuilder = TypeSpec.annotationBuilder(model.getSharpScopeName())
                .addAnnotation(ClassName.get(ScopeUtils.getScopeElement()))
                .addAnnotation(runtimeRetention)
                .addModifiers(Modifier.PUBLIC)
                .build();

        try {
            JavaFile.builder(model.packageString, generatedScopeBuilder)
                    .addFileComment("Generated by DaggerSharpener")
                    .build()
                    .writeTo(processingEnvironment.getFiler());
        } catch (IOException e) {
            e.printStackTrace();
            MessagerWrapper.logError(String.format("Could not generate scope '%s' for component '%s'", model.getSharpScopeName(), model.getComponentName()));
        }

        // return generatedScopeBuilder;
    }
}
