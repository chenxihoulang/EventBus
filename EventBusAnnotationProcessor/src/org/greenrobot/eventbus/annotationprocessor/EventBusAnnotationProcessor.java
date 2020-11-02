/*
 * Copyright (C) 2012-2016 Markus Junginger, greenrobot (http://greenrobot.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.greenrobot.eventbus.annotationprocessor;

import net.ltgt.gradle.incap.IncrementalAnnotationProcessor;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import de.greenrobot.common.ListMap;


import static net.ltgt.gradle.incap.IncrementalAnnotationProcessorType.AGGREGATING;

/**
 * Is an aggregating processor as it writes a single file, the subscriber index file,
 * based on found elements with the @Subscriber annotation.
 * 注解处理器:使用手动创建res->META-INF.services->javax.annotation.processing.Processor文件,
 * 并在文件中每一行写入一个注解处理器的全路径名称
 */
@SupportedAnnotationTypes("org.greenrobot.eventbus.Subscribe")//需要处理的注解全路径名
@SupportedOptions(value = {"eventBusIndex", "verbose"})//编译选项参数
@IncrementalAnnotationProcessor(AGGREGATING)
public class EventBusAnnotationProcessor extends AbstractProcessor {
    /**
     * 编译选项参数:用于控制生成的类文件的全路径名
     */
    public static final String OPTION_EVENT_BUS_INDEX = "eventBusIndex";
    /**
     * 编译选项参数:用于控制是否打印详细日志
     */
    public static final String OPTION_VERBOSE = "verbose";

    /**
     * Found subscriber methods for a class (without superclasses).
     * 存储类型和类型里面包含的所有EventBus回调方法,格式:TypeElement:List<ExecutableElement>
     * 一个TypeElement类型里面可以有多个ExecutableElement回调方法
     */
    private final ListMap<TypeElement, ExecutableElement> methodsByClass = new ListMap<>();
    /**
     * 包含了被Subscribe注解的方法,但是这个方法所属的类对生成的代码包不可见,所以需要跳过
     */
    private final Set<TypeElement> classesToSkip = new HashSet<>();
    /**
     * 写文件是否已经完毕
     */
    private boolean writerRoundDone;
    /**
     * process方法执行的总次数
     */
    private int round;
    /**
     * 是否打印详细日志
     */
    private boolean verbose;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    /**
     * 通过实现Processor接口可以自定义注解处理器
     * <p>
     * 注解处理器代码编写的常用逻辑:
     * 1.获取选项参数
     * 2.获取对应的注解信息,并将注解的对象相关信息进行保存
     * 3.根据保存的信息,生成代码
     *
     * @param annotations 请求处理注解类型的集合（也就是我们通过重写getSupportedAnnotationTypes方法所指定的注解类型）
     * @param env         是有关当前和上一次 循环的信息的环境
     * @return
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
        //用于打印日志
        Messager messager = processingEnv.getMessager();
        try {
            //获取编译选项,这里其实就是生成的类的全路径名
            String index = processingEnv.getOptions().get(OPTION_EVENT_BUS_INDEX);
            if (index == null) {
                messager.printMessage(Diagnostic.Kind.ERROR, "No option " + OPTION_EVENT_BUS_INDEX +
                        " passed to annotation processor");
                return false;
            }

            //是否大小详细日志
            verbose = Boolean.parseBoolean(processingEnv.getOptions().get(OPTION_VERBOSE));

            int lastPeriod = index.lastIndexOf('.');
            //包名
            String indexPackage = lastPeriod != -1 ? index.substring(0, lastPeriod) : null;

            round++;

            //打印执行次数,注解信息,是否处理完成
            if (verbose) {
                messager.printMessage(Diagnostic.Kind.NOTE, "Processing round " + round + ", new annotations: " +
                        !annotations.isEmpty() + ", processingOver: " + env.processingOver());
            }

            //如果循环处理完成返回true，否则返回false
            if (env.processingOver()) {
                //请求处理的注解集合不为空
                if (!annotations.isEmpty()) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                            "Unexpected processing state: annotations still available after processing over");
                    return false;
                }
            }

            if (annotations.isEmpty()) {
                return false;
            }

            if (writerRoundDone) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "Unexpected processing state: annotations still available after writing.");
            }

            //收集订阅回调方法信息,执行完后methodsByClass变量中已经有了类和对应的方法信息了
            collectSubscribers(annotations, env, messager);
            //被注解的方法如果在生成的类文件中不可见,则跳过
            checkForSubscribersToSkip(messager, indexPackage);

            if (!methodsByClass.isEmpty()) {
                //创建代码文件
                createInfoIndexFile(index);
            } else {
                messager.printMessage(Diagnostic.Kind.WARNING, "No @Subscribe annotations found");
            }
            writerRoundDone = true;
        } catch (RuntimeException e) {
            // IntelliJ does not handle exceptions nicely, so log and print a message
            e.printStackTrace();
            messager.printMessage(Diagnostic.Kind.ERROR, "Unexpected error in EventBusAnnotationProcessor: " + e);
        }
        return true;
    }

    /**
     * 收集订阅回调方法信息,遍历所有的注解类型元素,找到被改注解的元素
     *
     * @param annotations 指的是我们要处理的主句,这里是:org.greenrobot.eventbus.Subscribe注解
     * @param env         有关当前和上一次 循环的信息的环境
     * @param messager    打印日志的工具
     */
    private void collectSubscribers(Set<? extends TypeElement> annotations, RoundEnvironment env, Messager messager) {
        for (TypeElement annotation : annotations) {
            Set<? extends Element> elements = env.getElementsAnnotatedWith(annotation);
            //我们这里其实不用遍历,因为只有一个注解,可以直接使用下面的方法获取被Subscribe注解的所有元素
            // Set<? extends Element> elementsAnnotatedWith = env.getElementsAnnotatedWith(Subscribe.class);
            for (Element element : elements) {
                //Subscribe只能注解在方法上,所以是一个ExecutableElement
                if (element instanceof ExecutableElement) {
                    ExecutableElement method = (ExecutableElement) element;
                    //校验被Subscribe注解的方法签名是否正确:public static 有且仅有1个参数
                    if (checkHasNoErrors(method, messager)) {
                        //获取方法所属的类信息
                        TypeElement classElement = (TypeElement) method.getEnclosingElement();

                        //将类和方法对应起来,保存到ListMap中,一个类可以对应多个方法
                        methodsByClass.putElement(classElement, method);
                    }
                } else {
                    messager.printMessage(Diagnostic.Kind.ERROR, "@Subscribe is only valid for methods", element);
                }
            }
        }
    }

    /**
     * 校验被Subscribe注解的方法签名是否正确:public static 有且仅有1个参数
     */
    private boolean checkHasNoErrors(ExecutableElement element, Messager messager) {
        if (element.getModifiers().contains(Modifier.STATIC)) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Subscriber method must not be static", element);
            return false;
        }

        if (!element.getModifiers().contains(Modifier.PUBLIC)) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Subscriber method must be public", element);
            return false;
        }

        //获取方法的参数信息
        List<? extends VariableElement> parameters = element.getParameters();
        if (parameters.size() != 1) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Subscriber method must have exactly 1 parameter", element);
            return false;
        }
        return true;
    }

    /**
     * Subscriber classes should be skipped if their class or any involved event class are not visible to the index.
     * 被注解的方法如果在生成的类文件中不可见,则跳过
     */
    private void checkForSubscribersToSkip(Messager messager, String myPackage) {
        //遍历所有包含被Subscribe注解的类
        for (TypeElement skipCandidate : methodsByClass.keySet()) {
            TypeElement subscriberClass = skipCandidate;
            while (subscriberClass != null) {
                //判断subscriberClass是否对myPackage可见
                if (!isVisible(myPackage, subscriberClass)) {
                    //返回值用来防止重复打印日志
                    boolean added = classesToSkip.add(skipCandidate);
                    if (added) {
                        String msg;
                        if (subscriberClass.equals(skipCandidate)) {
                            msg = "Falling back to reflection because class is not public";
                        } else {
                            msg = "Falling back to reflection because " + skipCandidate +
                                    " has a non-public super class";
                        }
                        messager.printMessage(Diagnostic.Kind.NOTE, msg, subscriberClass);
                    }
                    break;
                }

                //获取这个类包含的所有被Subscribe注解的方法
                List<ExecutableElement> methods = methodsByClass.get(subscriberClass);
                if (methods != null) {
                    for (ExecutableElement method : methods) {
                        String skipReason = null;

                        //获取方法仅有的1个参数
                        VariableElement param = method.getParameters().get(0);

                        //获取参数的类型信息
                        TypeMirror typeMirror = getParamTypeMirror(param, messager);

                        //参数的类型不是一个类or接口类型
                        if (!(typeMirror instanceof DeclaredType) ||
                                !(((DeclaredType) typeMirror).asElement() instanceof TypeElement)) {
                            skipReason = "event type cannot be processed";
                        }

                        if (skipReason == null) {
                            TypeElement eventTypeElement = (TypeElement) ((DeclaredType) typeMirror).asElement();
                            //判断方法的参数的类型是否对生成的包可见
                            if (!isVisible(myPackage, eventTypeElement)) {
                                skipReason = "event type is not public";
                            }
                        }

                        //如果参数类型不对or方法不可见,则跳过
                        if (skipReason != null) {
                            boolean added = classesToSkip.add(skipCandidate);
                            if (added) {
                                String msg = "Falling back to reflection because " + skipReason;
                                if (!subscriberClass.equals(skipCandidate)) {
                                    msg += " (found in super class for " + skipCandidate + ")";
                                }
                                messager.printMessage(Diagnostic.Kind.NOTE, msg, param);
                            }
                            break;
                        }
                    }
                }
                subscriberClass = getSuperclass(subscriberClass);
            }
        }
    }

    /**
     * TypeMirror是一个接口，表示 Java 编程语言中的类型。
     * 这些类型包括基本类型、声明类型（类和接口类型）、数组类型、类型变量和 null 类型。
     * 还可以表示通配符类型参数、executable 的签名和返回类型，以及对应于包和关键字 void 的伪类型。
     *
     * @param element
     * @param messager
     * @return
     */
    private TypeMirror getParamTypeMirror(VariableElement element, Messager messager) {
        //变量的类型
        TypeMirror typeMirror = element.asType();
        // Check for generic type
        //是否是泛型类型
        if (typeMirror instanceof TypeVariable) {
            //? extends A
            TypeMirror upperBound = ((TypeVariable) typeMirror).getUpperBound();
            if (upperBound instanceof DeclaredType) {
                if (messager != null) {
                    messager.printMessage(Diagnostic.Kind.NOTE, "Using upper bound type " + upperBound +
                            " for generic parameter", element);
                }
                typeMirror = upperBound;
            }
        }
        return typeMirror;
    }

    /**
     * 获取某个类型的父类
     *
     * @param type
     * @return
     */
    private TypeElement getSuperclass(TypeElement type) {
        //类or接口
        if (type.getSuperclass().getKind() == TypeKind.DECLARED) {
            //获取父类
            TypeElement superclass = (TypeElement) processingEnv.getTypeUtils().asElement(type.getSuperclass());
            //获取父类的全限定名称
            String name = superclass.getQualifiedName().toString();
            if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.")) {
                // Skip system classes, this just degrades performance
                return null;
            } else {
                return superclass;
            }
        } else {
            return null;
        }
    }

    /**
     * 获取类的字符串表示形式
     *
     * @param typeElement
     * @param myPackage
     * @return
     */
    private String getClassString(TypeElement typeElement, String myPackage) {
        PackageElement packageElement = getPackageElement(typeElement);
        //包名
        String packageString = packageElement.getQualifiedName().toString();

        //类的全限定名称
        String className = typeElement.getQualifiedName().toString();
        if (packageString != null && !packageString.isEmpty()) {
            if (packageString.equals(myPackage)) {
                className = cutPackage(myPackage, className);
            } else if (packageString.equals("java.lang")) {
                className = typeElement.getSimpleName().toString();
            }
        }
        return className;
    }

    /**
     * 在某个包下面的类,裁剪出类名,包名去掉
     * @param paket
     * @param className
     * @return
     */
    private String cutPackage(String paket, String className) {
        if (className.startsWith(paket + '.')) {
            // Don't use TypeElement.getSimpleName, it doesn't work for us with inner classes
            return className.substring(paket.length() + 1);
        } else {
            // Paranoia
            throw new IllegalStateException("Mismatching " + paket + " vs. " + className);
        }
    }

    /**
     * 获取某个类型的包名
     */
    private PackageElement getPackageElement(TypeElement subscriberClass) {
        Element candidate = subscriberClass.getEnclosingElement();
        while (!(candidate instanceof PackageElement)) {
            candidate = candidate.getEnclosingElement();
        }
        return (PackageElement) candidate;
    }

    /**
     * 写入被Subscribe注解修饰的方法信息
     * @param writer
     * @param methods
     * @param callPrefix
     * @param myPackage
     * @throws IOException
     */
    private void writeCreateSubscriberMethods(BufferedWriter writer, List<ExecutableElement> methods,
                                              String callPrefix, String myPackage) throws IOException {
        for (ExecutableElement method : methods) {
            //获取方法参数
            List<? extends VariableElement> parameters = method.getParameters();
            //获取仅有的1个参数的类型
            TypeMirror paramType = getParamTypeMirror(parameters.get(0), null);
            //获取参数的类型对应的TypeElement
            TypeElement paramElement = (TypeElement) processingEnv.getTypeUtils().asElement(paramType);

            String methodName = method.getSimpleName().toString();
            //获取类型参数的字符串表示形式
            String eventClass = getClassString(paramElement, myPackage) + ".class";

            //下面解析注解参数
            Subscribe subscribe = method.getAnnotation(Subscribe.class);
            List<String> parts = new ArrayList<>();
            parts.add(callPrefix + "(\"" + methodName + "\",");
            String lineEnd = "),";
            if (subscribe.priority() == 0 && !subscribe.sticky()) {
                if (subscribe.threadMode() == ThreadMode.POSTING) {
                    parts.add(eventClass + lineEnd);
                } else {
                    parts.add(eventClass + ",");
                    parts.add("ThreadMode." + subscribe.threadMode().name() + lineEnd);
                }
            } else {
                parts.add(eventClass + ",");
                parts.add("ThreadMode." + subscribe.threadMode().name() + ",");
                parts.add(subscribe.priority() + ",");
                parts.add(subscribe.sticky() + lineEnd);
            }
            writeLine(writer, 3, parts.toArray(new String[parts.size()]));

            if (verbose) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Indexed @Subscribe at " +
                        method.getEnclosingElement().getSimpleName() + "." + methodName +
                        "(" + paramElement.getSimpleName() + ")");
            }

        }
    }

    /**
     * 创建代码文件
     *
     * @param index 类全限定名称
     */
    private void createInfoIndexFile(String index) {
        BufferedWriter writer = null;
        try {
            JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(index);
            int period = index.lastIndexOf('.');
            String myPackage = period > 0 ? index.substring(0, period) : null;
            String clazz = index.substring(period + 1);
            writer = new BufferedWriter(sourceFile.openWriter());

            if (myPackage != null) {
                writer.write("package " + myPackage + ";\n\n");
            }
            writer.write("import org.greenrobot.eventbus.meta.SimpleSubscriberInfo;\n");
            writer.write("import org.greenrobot.eventbus.meta.SubscriberMethodInfo;\n");
            writer.write("import org.greenrobot.eventbus.meta.SubscriberInfo;\n");
            writer.write("import org.greenrobot.eventbus.meta.SubscriberInfoIndex;\n\n");
            writer.write("import org.greenrobot.eventbus.ThreadMode;\n\n");
            writer.write("import java.util.HashMap;\n");
            writer.write("import java.util.Map;\n\n");
            writer.write("/** This class is generated by EventBus, do not edit. */\n");
            writer.write("public class " + clazz + " implements SubscriberInfoIndex {\n");
            writer.write("    private static final Map<Class<?>, SubscriberInfo> SUBSCRIBER_INDEX;\n\n");
            writer.write("    static {\n");
            writer.write("        SUBSCRIBER_INDEX = new HashMap<Class<?>, SubscriberInfo>();\n\n");

            //写入类和方法信息
            writeIndexLines(writer, myPackage);

            writer.write("    }\n\n");
            writer.write("    private static void putIndex(SubscriberInfo info) {\n");
            writer.write("        SUBSCRIBER_INDEX.put(info.getSubscriberClass(), info);\n");
            writer.write("    }\n\n");
            writer.write("    @Override\n");
            writer.write("    public SubscriberInfo getSubscriberInfo(Class<?> subscriberClass) {\n");
            writer.write("        SubscriberInfo info = SUBSCRIBER_INDEX.get(subscriberClass);\n");
            writer.write("        if (info != null) {\n");
            writer.write("            return info;\n");
            writer.write("        } else {\n");
            writer.write("            return null;\n");
            writer.write("        }\n");
            writer.write("    }\n");
            writer.write("}\n");
        } catch (IOException e) {
            throw new RuntimeException("Could not write source for " + index, e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    //Silent
                }
            }
        }
    }

    /**
     * 将被Subscribe注解修饰的方法进行包装成SimpleSubscriberInfo对象,然后添加到生成的代码中
     *
     * @param writer
     * @param myPackage
     * @throws IOException
     */
    private void writeIndexLines(BufferedWriter writer, String myPackage) throws IOException {
        for (TypeElement subscriberTypeElement : methodsByClass.keySet()) {
            //跳过被忽略的类
            if (classesToSkip.contains(subscriberTypeElement)) {
                continue;
            }

            //获取类的字符串表示形式
            String subscriberClass = getClassString(subscriberTypeElement, myPackage);
            if (isVisible(myPackage, subscriberTypeElement)) {
                writeLine(writer, 2,
                        "putIndex(new SimpleSubscriberInfo(" + subscriberClass + ".class,",
                        "true,", "new SubscriberMethodInfo[] {");
                List<ExecutableElement> methods = methodsByClass.get(subscriberTypeElement);
                writeCreateSubscriberMethods(writer, methods, "new SubscriberMethodInfo", myPackage);
                writer.write("        }));\n\n");
            } else {
                writer.write("        // Subscriber not visible to index: " + subscriberClass + "\n");
            }
        }
    }

    /**
     * 判断类是否对某个包可见
     */
    private boolean isVisible(String myPackage, TypeElement typeElement) {
        //获取类的修饰符
        Set<Modifier> modifiers = typeElement.getModifiers();
        boolean visible;
        if (modifiers.contains(Modifier.PUBLIC)) {
            visible = true;
        } else if (modifiers.contains(Modifier.PRIVATE) || modifiers.contains(Modifier.PROTECTED)) {
            visible = false;
        } else {
            //判断类所属的包名是否和myPackage一致
            String subscriberPackage = getPackageElement(typeElement).getQualifiedName().toString();
            if (myPackage == null) {
                visible = subscriberPackage.length() == 0;
            } else {
                visible = myPackage.equals(subscriberPackage);
            }
        }
        return visible;
    }

    private void writeLine(BufferedWriter writer, int indentLevel, String... parts) throws IOException {
        writeLine(writer, indentLevel, 2, parts);
    }

    private void writeLine(BufferedWriter writer, int indentLevel, int indentLevelIncrease, String... parts)
            throws IOException {
        writeIndent(writer, indentLevel);
        int len = indentLevel * 4;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i != 0) {
                if (len + part.length() > 118) {
                    writer.write("\n");
                    if (indentLevel < 12) {
                        indentLevel += indentLevelIncrease;
                    }
                    writeIndent(writer, indentLevel);
                    len = indentLevel * 4;
                } else {
                    writer.write(" ");
                }
            }
            writer.write(part);
            len += part.length();
        }
        writer.write("\n");
    }

    private void writeIndent(BufferedWriter writer, int indentLevel) throws IOException {
        for (int i = 0; i < indentLevel; i++) {
            writer.write("    ");
        }
    }
}
