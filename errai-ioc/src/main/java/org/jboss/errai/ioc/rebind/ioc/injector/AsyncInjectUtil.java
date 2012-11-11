/*
 * Copyright 2011 JBoss, by Red Hat, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.errai.ioc.rebind.ioc.injector;

import org.jboss.errai.codegen.Cast;
import org.jboss.errai.codegen.Context;
import org.jboss.errai.codegen.DefParameters;
import org.jboss.errai.codegen.Parameter;
import org.jboss.errai.codegen.Statement;
import org.jboss.errai.codegen.builder.AnonymousClassStructureBuilder;
import org.jboss.errai.codegen.builder.BlockBuilder;
import org.jboss.errai.codegen.exception.UnproxyableClassException;
import org.jboss.errai.codegen.meta.MetaClass;
import org.jboss.errai.codegen.meta.MetaClassFactory;
import org.jboss.errai.codegen.meta.MetaConstructor;
import org.jboss.errai.codegen.meta.MetaField;
import org.jboss.errai.codegen.meta.MetaMethod;
import org.jboss.errai.codegen.meta.MetaParameter;
import org.jboss.errai.codegen.util.Refs;
import org.jboss.errai.codegen.util.Stmt;
import org.jboss.errai.ioc.client.container.SimpleCreationalContext;
import org.jboss.errai.ioc.client.container.async.CreationalCallback;
import org.jboss.errai.ioc.rebind.ioc.bootstrapper.IOCProcessingContext;
import org.jboss.errai.ioc.rebind.ioc.exception.InjectionFailure;
import org.jboss.errai.ioc.rebind.ioc.exception.UnsatisfiedDependenciesException;
import org.jboss.errai.ioc.rebind.ioc.injector.api.AsyncDecoratorTask;
import org.jboss.errai.ioc.rebind.ioc.injector.api.AsyncInjectionTask;
import org.jboss.errai.ioc.rebind.ioc.injector.api.ConstructionStatusCallback;
import org.jboss.errai.ioc.rebind.ioc.injector.api.ConstructionStrategy;
import org.jboss.errai.ioc.rebind.ioc.injector.api.ConstructionType;
import org.jboss.errai.ioc.rebind.ioc.injector.api.InjectableInstance;
import org.jboss.errai.ioc.rebind.ioc.injector.api.InjectionContext;
import org.jboss.errai.ioc.rebind.ioc.injector.api.TaskType;
import org.jboss.errai.ioc.rebind.ioc.injector.async.AsyncProxyInjector;
import org.jboss.errai.ioc.rebind.ioc.injector.basic.TypeInjector;
import org.jboss.errai.ioc.rebind.ioc.metadata.QualifyingMetadata;
import org.mvel2.util.ReflectionUtil;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class AsyncInjectUtil {
  public static ConstructionStrategy getConstructionStrategy(final Injector injector, final InjectionContext ctx) {
    final MetaClass type = injector.getInjectedType();

    final List<AsyncInjectionTask> injectionTasks = new ArrayList<AsyncInjectionTask>();

    final List<MetaConstructor> constructorInjectionPoints
        = scanForConstructorInjectionPoints(injector, ctx, type, injectionTasks);

    injectionTasks.addAll(scanForTasks(injector, ctx, type));

    final List<MetaMethod> postConstructTasks = InjectUtil.scanForPostConstruct(type);
    final List<MetaMethod> preDestroyTasks = InjectUtil.scanForPreDestroy(type);

    for (final Class<? extends Annotation> a : ctx.getDecoratorAnnotationsBy(ElementType.TYPE)) {
      if (type.isAnnotationPresent(a)) {
        final AsyncDecoratorTask task = new AsyncDecoratorTask(injector, type, ctx.getDecorator(a));
        injectionTasks.add(task);
      }
    }

    if (!constructorInjectionPoints.isEmpty()) {
      if (constructorInjectionPoints.size() > 1) {
        throw new InjectionFailure("more than one constructor in "
            + type.getFullyQualifiedName() + " is marked as the injection point!");
      }

      final MetaConstructor constructor = constructorInjectionPoints.get(0);

      return new ConstructionStrategy() {
        @Override
        public void generateConstructor(final ConstructionStatusCallback callback) {
          final Statement[] parameterStatements
              = resolveInjectionDependencies(constructor.getParameters(), ctx, constructor);

          if (injector.isSingleton() && injector.isCreated()) return;

          final IOCProcessingContext processingContext = ctx.getProcessingContext();

          final BlockBuilder<AnonymousClassStructureBuilder> runBlock = Stmt.newObject(Runnable.class)
              .extend().publicOverridesMethod("run");

          runBlock.append(Stmt.declareFinalVariable(injector.getInstanceVarName(), type, Stmt.newObject(type, parameterStatements)));

          final Statement finishedCallback = runBlock
              .append(Stmt.loadVariable("async").invoke("setConstructedObject", Refs.get(injector.getInstanceVarName())))
              .finish()
          .finish();

          processingContext.append(Stmt.loadVariable("async").invoke("setOnConstruct", finishedCallback));

          processingContext.pushBlockBuilder(runBlock);

          callback.beanConstructed(ConstructionType.CONSTRUCTOR);

          handleAsyncInjectionTasks(ctx, injectionTasks);

          InjectUtil.doPostConstruct(ctx, injector, postConstructTasks);
          InjectUtil.doPreDestroy(ctx, injector, preDestroyTasks);

          processingContext.popBlockBuilder();
        }
      };
    }
    else {
      // field injection
      if (!InjectUtil.hasDefaultConstructor(type))
        throw new InjectionFailure("there is no public default constructor or suitable injection constructor for type: "
            + type.getFullyQualifiedName());

      return new ConstructionStrategy() {
        @Override
        public void generateConstructor(final ConstructionStatusCallback callback) {
          if (injector.isSingleton() && injector.isCreated()) return;

          final IOCProcessingContext processingContext = ctx.getProcessingContext();

          processingContext.append(
              Stmt.declareVariable(type)
                  .asFinal()
                  .named(injector.getInstanceVarName())
                  .initializeWith(Stmt.newObject(type))

          );

          callback.beanConstructed(ConstructionType.FIELD);

          handleAsyncInjectionTasks(ctx, injectionTasks);

          InjectUtil.doPostConstruct(ctx, injector, postConstructTasks);
          InjectUtil.doPreDestroy(ctx, injector, preDestroyTasks);
        }
      };
    }
  }

  private static void handleAsyncInjectionTasks(final InjectionContext ctx,
                                                final List<AsyncInjectionTask> tasks) {
    for (final AsyncInjectionTask task : tasks) {
      if (!task.doTask(ctx)) {
        throw new InjectionFailure("could perform injection task: " + task);
      }
    }
  }


  private static List<AsyncInjectionTask> scanForTasks(final Injector injector,
                                                       final InjectionContext ctx,
                                                       final MetaClass type) {
    final List<AsyncInjectionTask> accumulator = new ArrayList<AsyncInjectionTask>();
    final Set<Class<? extends Annotation>> decorators = ctx.getDecoratorAnnotations();

    for (final Class<? extends Annotation> decorator : decorators) {
      if (type.isAnnotationPresent(decorator)) {
        accumulator.add(new AsyncInjectionTask(injector, type));
      }
    }

    MetaClass visit = type;

    do {
      for (final MetaField field : visit.getDeclaredFields()) {
        if (InjectUtil.isInjectionPoint(ctx, field)) {
          if (!field.isPublic()) {
            final MetaMethod meth = visit.getMethod(ReflectionUtil.getSetter(field.getName()),
                field.getType());

            if (meth == null) {
              final AsyncInjectionTask task = new AsyncInjectionTask(injector, field);
              accumulator.add(task);
            }
            else {
              final AsyncInjectionTask task = new AsyncInjectionTask(injector, meth);
              accumulator.add(task);
            }
          }
          else {
            accumulator.add(new AsyncInjectionTask(injector, field));
          }
        }

        ElementType[] elTypes;
        for (final Class<? extends Annotation> a : decorators) {
          elTypes = a.isAnnotationPresent(Target.class) ? a.getAnnotation(Target.class).value()
              : new ElementType[]{ElementType.FIELD};

          for (final ElementType elType : elTypes) {
            switch (elType) {
              case FIELD:
                if (field.isAnnotationPresent(a)) {
                  accumulator.add(new AsyncDecoratorTask(injector, field, ctx.getDecorator(a)));
                }
                break;
            }
          }
        }
      }

      for (final MetaMethod meth : visit.getDeclaredMethods()) {
        if (InjectUtil.isInjectionPoint(ctx, meth)) {
          accumulator.add(new AsyncInjectionTask(injector, meth));
        }

        for (final Class<? extends Annotation> a : decorators) {
          final ElementType[] elTypes = a.isAnnotationPresent(Target.class) ? a.getAnnotation(Target.class).value()
              : new ElementType[]{ElementType.FIELD};

          for (final ElementType elType : elTypes) {
            switch (elType) {
              case METHOD:
                if (meth.isAnnotationPresent(a)) {
                  accumulator.add(new AsyncDecoratorTask(injector, meth, ctx.getDecorator(a)));
                }
                break;
              case PARAMETER:
                for (final MetaParameter parameter : meth.getParameters()) {
                  if (parameter.isAnnotationPresent(a)) {
                    final AsyncDecoratorTask task = new AsyncDecoratorTask(injector, parameter, ctx.getDecorator(a));
                    accumulator.add(task);
                  }
                }
            }
          }
        }
      }
    }
    while ((visit = visit.getSuperClass()) != null);

    return accumulator;
  }

  private static List<MetaConstructor> scanForConstructorInjectionPoints(final Injector injector,
                                                                         final InjectionContext ctx,
                                                                         final MetaClass type,
                                                                         final List<AsyncInjectionTask> tasks) {
    final List<MetaConstructor> accumulator = new ArrayList<MetaConstructor>();
    final Set<Class<? extends Annotation>> decorators = ctx.getDecoratorAnnotations();

    for (final MetaConstructor cns : type.getConstructors()) {
      if (InjectUtil.isInjectionPoint(ctx, cns)) {
        accumulator.add(cns);
      }

      ElementType[] elTypes;
      for (final Class<? extends Annotation> a : decorators) {
        elTypes = a.isAnnotationPresent(Target.class) ? a.getAnnotation(Target.class).value()
            : new ElementType[]{ElementType.FIELD};

        for (final ElementType elType : elTypes) {
          switch (elType) {
            case CONSTRUCTOR:
              if (cns.isAnnotationPresent(a)) {
                tasks.add(new AsyncDecoratorTask(injector, cns, ctx.getDecorator(a)));
              }
              break;
            case PARAMETER:
              for (final MetaParameter parameter : cns.getParameters()) {
                if (parameter.isAnnotationPresent(a)) {
                  final AsyncDecoratorTask task = new AsyncDecoratorTask(injector, parameter, ctx.getDecorator(a));
                  tasks.add(task);
                }
              }
          }
        }
      }
    }

    return accumulator;
  }


  public static Statement getInjectorOrProxy(final InjectionContext ctx,
                                             final InjectableInstance injectableInstance,
                                             final MetaClass clazz,
                                             final QualifyingMetadata qualifyingMetadata) {

    return getInjectorOrProxy(ctx, injectableInstance, clazz, qualifyingMetadata, false);
  }


  public static Statement getInjectorOrProxy(final InjectionContext ctx,
                                             final InjectableInstance injectableInstance,
                                             final MetaClass clazz,
                                             final QualifyingMetadata qualifyingMetadata,
                                             final boolean alwaysProxyDependent) {

    if (ctx.isInjectableQualified(clazz, qualifyingMetadata)) {
      final Injector inj = ctx.getQualifiedInjector(clazz, qualifyingMetadata);

      /**
       * Special handling for cycles. If two beans directly depend on each other, we shimmy in a call to the
       * binding reference to check the context for the instance to avoid a hanging duplicate reference. It is to
       * ensure only one instance of each bean is created.
       */
      if (ctx.cycles(injectableInstance.getEnclosingType(), clazz) && inj instanceof TypeInjector) {
        return Stmt.castTo(SimpleCreationalContext.class, Stmt.loadVariable("context")).invoke("getInstanceOrNew",
            Refs.get(inj.getCreationalCallbackVarName()),
            inj.getInjectedType(), inj.getQualifyingMetadata().getQualifiers());
      }

      return ctx.getQualifiedInjector(clazz, qualifyingMetadata).getBeanInstance(injectableInstance);
    }
    else {
      //todo: refactor the BootstrapInjectionContext to provide a cleaner API for interface delegates

      // try to inject it
      try {
        if (ctx.isInjectorRegistered(clazz, qualifyingMetadata)) {
          final Injector inj = ctx.getQualifiedInjector(clazz, qualifyingMetadata);

          if (inj.isProvider()) {
            if (inj.isStatic()) {
              return inj.getBeanInstance(injectableInstance);
            }

            /**
             * Inform the caller that we are in a proxy and that the operation they're doing must
             * necessarily be done within the ProxyResolver resolve operation since this provider operation
             * relies on a bean which is not yet available.
             */
            ctx.recordCycle(inj.getEnclosingType(), injectableInstance.getEnclosingType());

            final AsyncProxyInjector proxyInject = getOrCreateProxy(ctx, inj.getEnclosingType(), qualifyingMetadata);

            boolean pushedProxy = false;

            try {
              if (injectableInstance.getTaskType() == TaskType.Parameter
                  && injectableInstance.getConstructor() != null) {
                // eek! a producer element is produced by this bean and injected into it's own constructor!
                final AsyncProxyInjector producedElementProxy
                    = getOrCreateProxy(ctx, inj.getInjectedType(), qualifyingMetadata);

                proxyInject.addProxyCloseStatement(Stmt.loadVariable("context")
                    .invoke("addBean", Stmt.load(inj.getInjectedType()),
                        qualifyingMetadata.getQualifiers(), inj.getBeanInstance(injectableInstance)));

                proxyInject.getBeanInstance(injectableInstance);

                return producedElementProxy.getBeanInstance(injectableInstance);
              }
              else {
                ctx.getProcessingContext().pushBlockBuilder(proxyInject.getProxyResolverBlockBuilder());
                pushedProxy = true;
                ctx.markOpenProxy();
                return proxyInject.getBeanInstance(injectableInstance);
              }
            }
            finally {
              if (!ctx.isProxyOpen() && pushedProxy) {
                ctx.getProcessingContext().popBlockBuilder();
              }
            }

          }
          else if (inj.isSoftDisabled()
              || (inj.isDependent() &&
              (!alwaysProxyDependent || !ctx.typeContainsGraphCycles(inj.getInjectedType())))) {

            inj.setEnabled(true);
            if (inj.isCreated() && !inj.isRendered()) {
              throw new InjectionFailure("unresolveable cycle on dependent scoped bean: "
                  + inj.getInjectedType().getFullyQualifiedName() + "; does the bean intersect with a normal scope?");
            }

            return inj.getBeanInstance(injectableInstance);
          }
        }
      }
      catch (InjectionFailure e) {
        e.printStackTrace();
      }

      if (!ctx.isTypeInjectable(clazz)) {
        ctx.recordCycle(clazz, injectableInstance.getEnclosingType());
        return getOrCreateProxy(ctx, clazz, qualifyingMetadata).getBeanInstance(injectableInstance);
      }
      else {
        throw new InjectionFailure("cannot resolve injectable bean for type: " + clazz.getFullyQualifiedName()
            + "; qualified by: " + qualifyingMetadata.toString());
      }
    }
  }

  public static AsyncProxyInjector getOrCreateProxy(final InjectionContext ctx,
                                                    final MetaClass clazz,
                                                    final QualifyingMetadata qualifyingMetadata) {
    final AsyncProxyInjector proxyInjector;
    if (ctx.isProxiedInjectorRegistered(clazz, qualifyingMetadata)) {
      proxyInjector = (AsyncProxyInjector)
          ctx.getProxiedInjector(clazz, qualifyingMetadata);
    }
    else {
      proxyInjector = new AsyncProxyInjector(ctx.getProcessingContext(), clazz, qualifyingMetadata);
      ctx.addProxiedInjector(proxyInjector);
    }
    return proxyInjector;
  }

  public static Statement[] resolveInjectionDependencies(final MetaParameter[] parms,
                                                         final InjectionContext ctx,
                                                         final MetaMethod method) {
    return resolveInjectionDependencies(parms, ctx, method, true);
  }

  public static Statement[] resolveInjectionDependencies(final MetaParameter[] parms,
                                                         final InjectionContext ctx,
                                                         final MetaMethod method,
                                                         final boolean inlineReference) {

    final MetaClass[] parmTypes = InjectUtil.parametersToClassTypeArray(parms);
    final Statement[] parmValues = new Statement[parmTypes.length];

    for (int i = 0; i < parmTypes.length; i++) {
      Statement stmt;
      try {
        final InjectableInstance injectableInstance = InjectableInstance.getParameterInjectedInstance(
            parms[i],
            null,
            ctx);

        stmt = getInjectorOrProxy(ctx, injectableInstance, parmTypes[i],
            ctx.getProcessingContext().getQualifyingMetadataFactory().createFrom(parms[i].getAnnotations()));

        if (inlineReference) {
          stmt = recordInlineReference(ctx, parms[i]);
        }
      }
      catch (InjectionFailure e) {
        e.setTarget(method.getDeclaringClass() + "." + method.getName() + DefParameters.from(method)
            .generate(Context.create()));
        throw e;
      }

      parmValues[i] = stmt;
    }

    return parmValues;
  }

  public static Statement[] resolveInjectionDependencies(final MetaParameter[] parms,
                                                         final InjectionContext ctx,
                                                         final MetaConstructor constructor) {
    final MetaClass[] parmTypes = InjectUtil.parametersToClassTypeArray(parms);
    final Statement[] parmValues = new Statement[parmTypes.length];

    for (int i = 0; i < parmTypes.length; i++
        ) {
      final Statement stmt;
      final MetaClass parmType = parmTypes[i];
      try {
        final MetaClass creationType = MetaClassFactory
            .parameterizedAs(CreationalCallback.class, MetaClassFactory.typeParametersOf(parmType));

        final Statement callback = Stmt.newObject(creationType).extend()
            .publicOverridesMethod("callback", Parameter.of(parmType, "beanInstance"))
              .append(Stmt.loadVariable("async").invoke("finish", Refs.get("this"), Refs.get("beanInstance")))
            .finish()
        .finish();

        final BlockBuilder<?> blockBuilder = ctx.getProcessingContext().getBlockBuilder();
        final String varNameFromType = InjectUtil.getVarNameFromType(parmType);
        blockBuilder.append(Stmt.declareFinalVariable(varNameFromType, creationType, callback));
        blockBuilder.append(Stmt.loadVariable("async").invoke("waitConstruct", Refs.get(varNameFromType)));

        // Get the injection value.
        getInjectorOrProxy(
            ctx,
            InjectableInstance.getParameterInjectedInstance(parms[i], null, ctx),
            parmType,
            ctx.getProcessingContext().getQualifyingMetadataFactory().createFrom(
                parms[i].getAnnotations()
            ),
            true
        );
        // Record the statement which can be used to access the reference to the injected bean in-line.
        // For instance, for code decoration.
        stmt = recordInlineReference(
            ctx,
            parms[i]
        );

        ctx.closeProxyIfOpen();
      }
      catch (UnproxyableClassException e) {
        final String err = "your object graph has cyclical dependencies and the cycle could not be proxied. " +
            "use of the @Dependent scope and @New qualifier may not " +
            "produce properly initalized objects for: " + parmType.getFullyQualifiedName() + "\n" +
            "\t Offending node: " + constructor.getDeclaringClass().getFullyQualifiedName() + "\n" +
            "\t Note          : this issue can be resolved by making "
            + e.getUnproxyableClass() + " proxyable. Introduce a default no-arg constructor and make sure the " +
            "class is non-final.";

        throw UnsatisfiedDependenciesException.createWithSingleParameterFailure(parms[i], constructor.getDeclaringClass(),
            parms[i].getType(), err);
      }
      catch (InjectionFailure e) {
        e.setTarget(constructor.getDeclaringClass() + "." + DefParameters.from(constructor)
            .generate(Context.create()));
        throw e;
      }

      parmValues[i] = stmt;
    }

    return parmValues;
  }

  private static Statement recordInlineReference(final InjectionContext ctx,
                                                 final MetaParameter parm) {

    final Statement stmt = Cast.to(parm.getType(), Stmt.loadVariable("async").invoke("getBeanValue",
        Refs.get(InjectUtil.getVarNameFromType(parm.getType()))));

    ctx.addInlineBeanReference(parm, stmt);
    return stmt;
  }
}
