package org.gradle.accessors.dm;

import org.gradle.api.NonNullApi;
import org.gradle.api.artifacts.MinimalExternalModuleDependency;
import org.gradle.plugin.use.PluginDependency;
import org.gradle.api.artifacts.ExternalModuleDependencyBundle;
import org.gradle.api.artifacts.MutableVersionConstraint;
import org.gradle.api.provider.Provider;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.internal.catalog.AbstractExternalDependencyFactory;
import org.gradle.api.internal.catalog.DefaultVersionCatalog;
import java.util.Map;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.internal.artifacts.dsl.CapabilityNotationParser;
import javax.inject.Inject;

/**
 * A catalog of dependencies accessible via the `libs` extension.
 */
@NonNullApi
public class LibrariesForLibsInPluginsBlock extends AbstractExternalDependencyFactory {

    private final AbstractExternalDependencyFactory owner = this;
    private final BioimageioLibraryAccessors laccForBioimageioLibraryAccessors = new BioimageioLibraryAccessors(owner);
    private final CommonmarkLibraryAccessors laccForCommonmarkLibraryAccessors = new CommonmarkLibraryAccessors(owner);
    private final CommonsLibraryAccessors laccForCommonsLibraryAccessors = new CommonsLibraryAccessors(owner);
    private final CudaLibraryAccessors laccForCudaLibraryAccessors = new CudaLibraryAccessors(owner);
    private final GroovyLibraryAccessors laccForGroovyLibraryAccessors = new GroovyLibraryAccessors(owner);
    private final IkonliLibraryAccessors laccForIkonliLibraryAccessors = new IkonliLibraryAccessors(owner);
    private final JunitLibraryAccessors laccForJunitLibraryAccessors = new JunitLibraryAccessors(owner);
    private final LogviewerLibraryAccessors laccForLogviewerLibraryAccessors = new LogviewerLibraryAccessors(owner);
    private final OpencvLibraryAccessors laccForOpencvLibraryAccessors = new OpencvLibraryAccessors(owner);
    private final QupathLibraryAccessors laccForQupathLibraryAccessors = new QupathLibraryAccessors(owner);
    private final VersionAccessors vaccForVersionAccessors = new VersionAccessors(providers, config);
    private final BundleAccessors baccForBundleAccessors = new BundleAccessors(objects, providers, config, attributesFactory, capabilityNotationParser);
    private final PluginAccessors paccForPluginAccessors = new PluginAccessors(providers, config);

    @Inject
    public LibrariesForLibsInPluginsBlock(DefaultVersionCatalog config, ProviderFactory providers, ObjectFactory objects, ImmutableAttributesFactory attributesFactory, CapabilityNotationParser capabilityNotationParser) {
        super(config, providers, objects, attributesFactory, capabilityNotationParser);
    }

        /**
         * Creates a dependency provider for controlsfx (org.controlsfx:controlsfx)
     * with versionRef 'controlsFX'.
         * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
     * @deprecated Will be removed in Gradle 9.0.
         */
    @Deprecated
        public Provider<MinimalExternalModuleDependency> getControlsfx() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
            return create("controlsfx");
    }

        /**
         * Creates a dependency provider for deepJavaLibrary (ai.djl:api)
     * with versionRef 'deepJavaLibrary'.
         * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
     * @deprecated Will be removed in Gradle 9.0.
         */
    @Deprecated
        public Provider<MinimalExternalModuleDependency> getDeepJavaLibrary() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
            return create("deepJavaLibrary");
    }

        /**
         * Creates a dependency provider for gson (com.google.code.gson:gson)
     * with versionRef 'gson'.
         * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
     * @deprecated Will be removed in Gradle 9.0.
         */
    @Deprecated
        public Provider<MinimalExternalModuleDependency> getGson() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
            return create("gson");
    }

        /**
         * Creates a dependency provider for guava (com.google.guava:guava)
     * with versionRef 'guava'.
         * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
     * @deprecated Will be removed in Gradle 9.0.
         */
    @Deprecated
        public Provider<MinimalExternalModuleDependency> getGuava() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
            return create("guava");
    }

        /**
         * Creates a dependency provider for imagej (net.imagej:ij)
     * with versionRef 'imagej'.
         * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
     * @deprecated Will be removed in Gradle 9.0.
         */
    @Deprecated
        public Provider<MinimalExternalModuleDependency> getImagej() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
            return create("imagej");
    }

        /**
         * Creates a dependency provider for javacpp (org.bytedeco:javacpp)
     * with versionRef 'javacpp'.
         * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
     * @deprecated Will be removed in Gradle 9.0.
         */
    @Deprecated
        public Provider<MinimalExternalModuleDependency> getJavacpp() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
            return create("javacpp");
    }

        /**
         * Creates a dependency provider for jfreesvg (org.jfree:org.jfree.svg)
     * with versionRef 'jfreeSvg'.
         * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
     * @deprecated Will be removed in Gradle 9.0.
         */
    @Deprecated
        public Provider<MinimalExternalModuleDependency> getJfreesvg() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
            return create("jfreesvg");
    }

        /**
         * Creates a dependency provider for jfxtras (org.jfxtras:jfxtras-menu)
     * with versionRef 'jfxtras'.
         * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
     * @deprecated Will be removed in Gradle 9.0.
         */
    @Deprecated
        public Provider<MinimalExternalModuleDependency> getJfxtras() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
            return create("jfxtras");
    }

        /**
         * Creates a dependency provider for jna (net.java.dev.jna:jna)
     * with versionRef 'jna'.
         * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
     * @deprecated Will be removed in Gradle 9.0.
         */
    @Deprecated
        public Provider<MinimalExternalModuleDependency> getJna() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
            return create("jna");
    }

        /**
         * Creates a dependency provider for jts (org.locationtech.jts:jts-core)
     * with versionRef 'jts'.
         * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
     * @deprecated Will be removed in Gradle 9.0.
         */
    @Deprecated
        public Provider<MinimalExternalModuleDependency> getJts() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
            return create("jts");
    }

        /**
         * Creates a dependency provider for logback (ch.qos.logback:logback-classic)
     * with versionRef 'logback'.
         * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
     * @deprecated Will be removed in Gradle 9.0.
         */
    @Deprecated
        public Provider<MinimalExternalModuleDependency> getLogback() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
            return create("logback");
    }

        /**
         * Creates a dependency provider for picocli (info.picocli:picocli)
     * with versionRef 'picocli'.
         * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
     * @deprecated Will be removed in Gradle 9.0.
         */
    @Deprecated
        public Provider<MinimalExternalModuleDependency> getPicocli() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
            return create("picocli");
    }

        /**
         * Creates a dependency provider for richtextfx (org.fxmisc.richtext:richtextfx)
     * with versionRef 'richtextfx'.
         * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
     * @deprecated Will be removed in Gradle 9.0.
         */
    @Deprecated
        public Provider<MinimalExternalModuleDependency> getRichtextfx() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
            return create("richtextfx");
    }

        /**
         * Creates a dependency provider for slf4j (org.slf4j:slf4j-api)
     * with versionRef 'slf4j'.
         * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
     * @deprecated Will be removed in Gradle 9.0.
         */
    @Deprecated
        public Provider<MinimalExternalModuleDependency> getSlf4j() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
            return create("slf4j");
    }

        /**
         * Creates a dependency provider for snakeyaml (org.yaml:snakeyaml)
     * with versionRef 'snakeyaml'.
         * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
     * @deprecated Will be removed in Gradle 9.0.
         */
    @Deprecated
        public Provider<MinimalExternalModuleDependency> getSnakeyaml() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
            return create("snakeyaml");
    }

    /**
     * Returns the group of libraries at bioimageio
     * @deprecated Will be removed in Gradle 9.0.
     */
    @Deprecated
    public BioimageioLibraryAccessors getBioimageio() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
        return laccForBioimageioLibraryAccessors;
    }

    /**
     * Returns the group of libraries at commonmark
     * @deprecated Will be removed in Gradle 9.0.
     */
    @Deprecated
    public CommonmarkLibraryAccessors getCommonmark() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
        return laccForCommonmarkLibraryAccessors;
    }

    /**
     * Returns the group of libraries at commons
     * @deprecated Will be removed in Gradle 9.0.
     */
    @Deprecated
    public CommonsLibraryAccessors getCommons() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
        return laccForCommonsLibraryAccessors;
    }

    /**
     * Returns the group of libraries at cuda
     * @deprecated Will be removed in Gradle 9.0.
     */
    @Deprecated
    public CudaLibraryAccessors getCuda() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
        return laccForCudaLibraryAccessors;
    }

    /**
     * Returns the group of libraries at groovy
     * @deprecated Will be removed in Gradle 9.0.
     */
    @Deprecated
    public GroovyLibraryAccessors getGroovy() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
        return laccForGroovyLibraryAccessors;
    }

    /**
     * Returns the group of libraries at ikonli
     * @deprecated Will be removed in Gradle 9.0.
     */
    @Deprecated
    public IkonliLibraryAccessors getIkonli() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
        return laccForIkonliLibraryAccessors;
    }

    /**
     * Returns the group of libraries at junit
     * @deprecated Will be removed in Gradle 9.0.
     */
    @Deprecated
    public JunitLibraryAccessors getJunit() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
        return laccForJunitLibraryAccessors;
    }

    /**
     * Returns the group of libraries at logviewer
     * @deprecated Will be removed in Gradle 9.0.
     */
    @Deprecated
    public LogviewerLibraryAccessors getLogviewer() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
        return laccForLogviewerLibraryAccessors;
    }

    /**
     * Returns the group of libraries at opencv
     * @deprecated Will be removed in Gradle 9.0.
     */
    @Deprecated
    public OpencvLibraryAccessors getOpencv() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
        return laccForOpencvLibraryAccessors;
    }

    /**
     * Returns the group of libraries at qupath
     * @deprecated Will be removed in Gradle 9.0.
     */
    @Deprecated
    public QupathLibraryAccessors getQupath() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
        return laccForQupathLibraryAccessors;
    }

    /**
     * Returns the group of versions at versions
     */
    public VersionAccessors getVersions() {
        return vaccForVersionAccessors;
    }

    /**
     * Returns the group of bundles at bundles
     * @deprecated Will be removed in Gradle 9.0.
     */
    @Deprecated
    public BundleAccessors getBundles() {
        org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
        return baccForBundleAccessors;
    }

    /**
     * Returns the group of plugins at plugins
     */
    public PluginAccessors getPlugins() {
        return paccForPluginAccessors;
    }

    /**
     * @deprecated Will be removed in Gradle 9.0.
     */
    @Deprecated
    public static class BioimageioLibraryAccessors extends SubDependencyFactory {

        public BioimageioLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

            /**
             * Creates a dependency provider for spec (io.github.qupath:qupath-bioimageio-spec)
         * with versionRef 'bioimageIoSpec'.
             * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
         * @deprecated Will be removed in Gradle 9.0.
             */
        @Deprecated
            public Provider<MinimalExternalModuleDependency> getSpec() {
            org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return create("bioimageio.spec");
        }

    }

    /**
     * @deprecated Will be removed in Gradle 9.0.
     */
    @Deprecated
    public static class CommonmarkLibraryAccessors extends SubDependencyFactory implements DependencyNotationSupplier {

        public CommonmarkLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

            /**
             * Creates a dependency provider for commonmark (org.commonmark:commonmark)
         * with versionRef 'commonmark'.
             * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
         * @deprecated Will be removed in Gradle 9.0.
             */
        @Deprecated
            public Provider<MinimalExternalModuleDependency> asProvider() {
            org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return create("commonmark");
        }

            /**
             * Creates a dependency provider for yaml (org.commonmark:commonmark-ext-yaml-front-matter)
         * with versionRef 'commonmark'.
             * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
         * @deprecated Will be removed in Gradle 9.0.
             */
        @Deprecated
            public Provider<MinimalExternalModuleDependency> getYaml() {
            org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return create("commonmark.yaml");
        }

    }

    /**
     * @deprecated Will be removed in Gradle 9.0.
     */
    @Deprecated
    public static class CommonsLibraryAccessors extends SubDependencyFactory {

        public CommonsLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

            /**
             * Creates a dependency provider for math (org.apache.commons:commons-math3)
         * with versionRef 'commonsMath3'.
             * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
         * @deprecated Will be removed in Gradle 9.0.
             */
        @Deprecated
            public Provider<MinimalExternalModuleDependency> getMath() {
            org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return create("commons.math");
        }

            /**
             * Creates a dependency provider for text (org.apache.commons:commons-text)
         * with versionRef 'commonsText'.
             * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
         * @deprecated Will be removed in Gradle 9.0.
             */
        @Deprecated
            public Provider<MinimalExternalModuleDependency> getText() {
            org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return create("commons.text");
        }

    }

    /**
     * @deprecated Will be removed in Gradle 9.0.
     */
    @Deprecated
    public static class CudaLibraryAccessors extends SubDependencyFactory implements DependencyNotationSupplier {

        public CudaLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

            /**
             * Creates a dependency provider for cuda (org.bytedeco:cuda-platform)
         * with versionRef 'cuda'.
             * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
         * @deprecated Will be removed in Gradle 9.0.
             */
        @Deprecated
            public Provider<MinimalExternalModuleDependency> asProvider() {
            org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return create("cuda");
        }

            /**
             * Creates a dependency provider for redist (org.bytedeco:cuda-platform-redist)
         * with versionRef 'cuda'.
             * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
         * @deprecated Will be removed in Gradle 9.0.
             */
        @Deprecated
            public Provider<MinimalExternalModuleDependency> getRedist() {
            org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return create("cuda.redist");
        }

    }

    /**
     * @deprecated Will be removed in Gradle 9.0.
     */
    @Deprecated
    public static class GroovyLibraryAccessors extends SubDependencyFactory {

        public GroovyLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

            /**
             * Creates a dependency provider for core (org.apache.groovy:groovy)
         * with versionRef 'groovy'.
             * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
         * @deprecated Will be removed in Gradle 9.0.
             */
        @Deprecated
            public Provider<MinimalExternalModuleDependency> getCore() {
            org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return create("groovy.core");
        }

            /**
             * Creates a dependency provider for jsr223 (org.apache.groovy:groovy-jsr223)
         * with versionRef 'groovy'.
             * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
         * @deprecated Will be removed in Gradle 9.0.
             */
        @Deprecated
            public Provider<MinimalExternalModuleDependency> getJsr223() {
            org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return create("groovy.jsr223");
        }

            /**
             * Creates a dependency provider for xml (org.apache.groovy:groovy-xml)
         * with versionRef 'groovy'.
             * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
         * @deprecated Will be removed in Gradle 9.0.
             */
        @Deprecated
            public Provider<MinimalExternalModuleDependency> getXml() {
            org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return create("groovy.xml");
        }

    }

    /**
     * @deprecated Will be removed in Gradle 9.0.
     */
    @Deprecated
    public static class IkonliLibraryAccessors extends SubDependencyFactory {

        public IkonliLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

            /**
             * Creates a dependency provider for ionicons4 (org.kordamp.ikonli:ikonli-ionicons4-pack)
         * with versionRef 'ikonli'.
             * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
         * @deprecated Will be removed in Gradle 9.0.
             */
        @Deprecated
            public Provider<MinimalExternalModuleDependency> getIonicons4() {
            org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return create("ikonli.ionicons4");
        }

            /**
             * Creates a dependency provider for javafx (org.kordamp.ikonli:ikonli-javafx)
         * with versionRef 'ikonli'.
             * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
         * @deprecated Will be removed in Gradle 9.0.
             */
        @Deprecated
            public Provider<MinimalExternalModuleDependency> getJavafx() {
            org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return create("ikonli.javafx");
        }

    }

    /**
     * @deprecated Will be removed in Gradle 9.0.
     */
    @Deprecated
    public static class JunitLibraryAccessors extends SubDependencyFactory implements DependencyNotationSupplier {

        public JunitLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

            /**
             * Creates a dependency provider for junit (org.junit.jupiter:junit-jupiter)
         * with versionRef 'junit'.
             * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
         * @deprecated Will be removed in Gradle 9.0.
             */
        @Deprecated
            public Provider<MinimalExternalModuleDependency> asProvider() {
            org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return create("junit");
        }

            /**
             * Creates a dependency provider for platform (org.junit.platform:junit-platform-launcher)
         * with no version specified
             * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
         * @deprecated Will be removed in Gradle 9.0.
             */
        @Deprecated
            public Provider<MinimalExternalModuleDependency> getPlatform() {
            org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return create("junit.platform");
        }

    }

    /**
     * @deprecated Will be removed in Gradle 9.0.
     */
    @Deprecated
    public static class LogviewerLibraryAccessors extends SubDependencyFactory implements DependencyNotationSupplier {

        public LogviewerLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

            /**
             * Creates a dependency provider for logviewer (io.github.qupath:logviewer-ui-main)
         * with versionRef 'logviewer'.
             * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
         * @deprecated Will be removed in Gradle 9.0.
             */
        @Deprecated
            public Provider<MinimalExternalModuleDependency> asProvider() {
            org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return create("logviewer");
        }

            /**
             * Creates a dependency provider for console (io.github.qupath:logviewer-ui-textarea)
         * with versionRef 'logviewer'.
             * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
         * @deprecated Will be removed in Gradle 9.0.
             */
        @Deprecated
            public Provider<MinimalExternalModuleDependency> getConsole() {
            org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return create("logviewer.console");
        }

            /**
             * Creates a dependency provider for logback (io.github.qupath:logviewer-logging-logback)
         * with versionRef 'logviewer'.
             * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
         * @deprecated Will be removed in Gradle 9.0.
             */
        @Deprecated
            public Provider<MinimalExternalModuleDependency> getLogback() {
            org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return create("logviewer.logback");
        }

            /**
             * Creates a dependency provider for rich (io.github.qupath:logviewer-ui-richtextfx)
         * with versionRef 'logviewer'.
             * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
         * @deprecated Will be removed in Gradle 9.0.
             */
        @Deprecated
            public Provider<MinimalExternalModuleDependency> getRich() {
            org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return create("logviewer.rich");
        }

    }

    /**
     * @deprecated Will be removed in Gradle 9.0.
     */
    @Deprecated
    public static class OpencvLibraryAccessors extends SubDependencyFactory implements DependencyNotationSupplier {

        public OpencvLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

            /**
             * Creates a dependency provider for opencv (org.bytedeco:opencv-platform)
         * with versionRef 'opencv'.
             * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
         * @deprecated Will be removed in Gradle 9.0.
             */
        @Deprecated
            public Provider<MinimalExternalModuleDependency> asProvider() {
            org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return create("opencv");
        }

            /**
             * Creates a dependency provider for gpu (org.bytedeco:opencv-platform-gpu)
         * with versionRef 'opencv'.
             * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
         * @deprecated Will be removed in Gradle 9.0.
             */
        @Deprecated
            public Provider<MinimalExternalModuleDependency> getGpu() {
            org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return create("opencv.gpu");
        }

    }

    /**
     * @deprecated Will be removed in Gradle 9.0.
     */
    @Deprecated
    public static class QupathLibraryAccessors extends SubDependencyFactory {

        public QupathLibraryAccessors(AbstractExternalDependencyFactory owner) { super(owner); }

            /**
             * Creates a dependency provider for fxtras (io.github.qupath:qupath-fxtras)
         * with versionRef 'qupath.fxtras'.
             * This dependency was declared in catalog io.github.qupath:qupath-catalog:0.5.0
         * @deprecated Will be removed in Gradle 9.0.
             */
        @Deprecated
            public Provider<MinimalExternalModuleDependency> getFxtras() {
            org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return create("qupath.fxtras");
        }

    }

    public static class VersionAccessors extends VersionFactory  {

        private final QupathVersionAccessors vaccForQupathVersionAccessors = new QupathVersionAccessors(providers, config);
        public VersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

            /**
             * Returns the version associated to this alias: bioformats (7.0.1)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getBioformats() { return getVersion("bioformats"); }

            /**
             * Returns the version associated to this alias: bioimageIoSpec (0.1.0)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getBioimageIoSpec() { return getVersion("bioimageIoSpec"); }

            /**
             * Returns the version associated to this alias: commonmark (0.21.0)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getCommonmark() { return getVersion("commonmark"); }

            /**
             * Returns the version associated to this alias: commonsMath3 (3.6.1)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getCommonsMath3() { return getVersion("commonsMath3"); }

            /**
             * Returns the version associated to this alias: commonsText (1.10.0)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getCommonsText() { return getVersion("commonsText"); }

            /**
             * Returns the version associated to this alias: controlsFX (11.1.2)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getControlsFX() { return getVersion("controlsFX"); }

            /**
             * Returns the version associated to this alias: cuda (11.8-8.6-1.5.8)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getCuda() { return getVersion("cuda"); }

            /**
             * Returns the version associated to this alias: deepJavaLibrary (0.24.0)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getDeepJavaLibrary() { return getVersion("deepJavaLibrary"); }

            /**
             * Returns the version associated to this alias: groovy (4.0.15)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getGroovy() { return getVersion("groovy"); }

            /**
             * Returns the version associated to this alias: gson (2.10.1)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getGson() { return getVersion("gson"); }

            /**
             * Returns the version associated to this alias: guava (32.1.3-jre)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getGuava() { return getVersion("guava"); }

            /**
             * Returns the version associated to this alias: ikonli (12.3.1)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getIkonli() { return getVersion("ikonli"); }

            /**
             * Returns the version associated to this alias: imagej (1.54f)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getImagej() { return getVersion("imagej"); }

            /**
             * Returns the version associated to this alias: javacpp (1.5.8)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getJavacpp() { return getVersion("javacpp"); }

            /**
             * Returns the version associated to this alias: javacppgradle (1.5.9)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getJavacppgradle() { return getVersion("javacppgradle"); }

            /**
             * Returns the version associated to this alias: javafx (20)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getJavafx() { return getVersion("javafx"); }

            /**
             * Returns the version associated to this alias: jdk (17)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getJdk() { return getVersion("jdk"); }

            /**
             * Returns the version associated to this alias: jfreeSvg (5.0.5)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getJfreeSvg() { return getVersion("jfreeSvg"); }

            /**
             * Returns the version associated to this alias: jfxtras (17-r1)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getJfxtras() { return getVersion("jfxtras"); }

            /**
             * Returns the version associated to this alias: jna (5.13.0)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getJna() { return getVersion("jna"); }

            /**
             * Returns the version associated to this alias: jts (1.19.0)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getJts() { return getVersion("jts"); }

            /**
             * Returns the version associated to this alias: junit (5.9.2)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getJunit() { return getVersion("junit"); }

            /**
             * Returns the version associated to this alias: logback (1.3.11)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getLogback() { return getVersion("logback"); }

            /**
             * Returns the version associated to this alias: logviewer (0.1.1)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getLogviewer() { return getVersion("logviewer"); }

            /**
             * Returns the version associated to this alias: opencv (4.6.0-1.5.8)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getOpencv() { return getVersion("opencv"); }

            /**
             * Returns the version associated to this alias: openslide (4.0.0)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getOpenslide() { return getVersion("openslide"); }

            /**
             * Returns the version associated to this alias: picocli (4.7.5)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getPicocli() { return getVersion("picocli"); }

            /**
             * Returns the version associated to this alias: richtextfx (0.11.2)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getRichtextfx() { return getVersion("richtextfx"); }

            /**
             * Returns the version associated to this alias: slf4j (2.0.9)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getSlf4j() { return getVersion("slf4j"); }

            /**
             * Returns the version associated to this alias: snakeyaml (2.2)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getSnakeyaml() { return getVersion("snakeyaml"); }

        /**
         * Returns the group of versions at versions.qupath
         */
        public QupathVersionAccessors getQupath() {
            return vaccForQupathVersionAccessors;
        }

    }

    public static class QupathVersionAccessors extends VersionFactory  {

        public QupathVersionAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

            /**
             * Returns the version associated to this alias: qupath.fxtras (0.1.3)
             * If the version is a rich version and that its not expressible as a
             * single version string, then an empty string is returned.
             * This version was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<String> getFxtras() { return getVersion("qupath.fxtras"); }

    }

    /**
     * @deprecated Will be removed in Gradle 9.0.
     */
    @Deprecated
    public static class BundleAccessors extends BundleFactory {
        private final OpencvBundleAccessors baccForOpencvBundleAccessors = new OpencvBundleAccessors(objects, providers, config, attributesFactory, capabilityNotationParser);

        public BundleAccessors(ObjectFactory objects, ProviderFactory providers, DefaultVersionCatalog config, ImmutableAttributesFactory attributesFactory, CapabilityNotationParser capabilityNotationParser) { super(objects, providers, config, attributesFactory, capabilityNotationParser); }

            /**
             * Creates a dependency bundle provider for groovy which is an aggregate for the following dependencies:
             * <ul>
             *    <li>org.apache.groovy:groovy</li>
             *    <li>org.apache.groovy:groovy-jsr223</li>
             *    <li>org.apache.groovy:groovy-xml</li>
             * </ul>
             * This bundle was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             * @deprecated Will be removed in Gradle 9.0.
             */
            @Deprecated
            public Provider<ExternalModuleDependencyBundle> getGroovy() {
                org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return createBundle("groovy");
            }

            /**
             * Creates a dependency bundle provider for ikonli which is an aggregate for the following dependencies:
             * <ul>
             *    <li>org.kordamp.ikonli:ikonli-javafx</li>
             *    <li>org.kordamp.ikonli:ikonli-ionicons4-pack</li>
             * </ul>
             * This bundle was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             * @deprecated Will be removed in Gradle 9.0.
             */
            @Deprecated
            public Provider<ExternalModuleDependencyBundle> getIkonli() {
                org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return createBundle("ikonli");
            }

            /**
             * Creates a dependency bundle provider for logging which is an aggregate for the following dependencies:
             * <ul>
             *    <li>org.slf4j:slf4j-api</li>
             *    <li>ch.qos.logback:logback-classic</li>
             * </ul>
             * This bundle was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             * @deprecated Will be removed in Gradle 9.0.
             */
            @Deprecated
            public Provider<ExternalModuleDependencyBundle> getLogging() {
                org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return createBundle("logging");
            }

            /**
             * Creates a dependency bundle provider for logviewer which is an aggregate for the following dependencies:
             * <ul>
             *    <li>io.github.qupath:logviewer-ui-main</li>
             *    <li>io.github.qupath:logviewer-ui-textarea</li>
             *    <li>io.github.qupath:logviewer-ui-richtextfx</li>
             *    <li>io.github.qupath:logviewer-logging-logback</li>
             * </ul>
             * This bundle was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             * @deprecated Will be removed in Gradle 9.0.
             */
            @Deprecated
            public Provider<ExternalModuleDependencyBundle> getLogviewer() {
                org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return createBundle("logviewer");
            }

            /**
             * Creates a dependency bundle provider for markdown which is an aggregate for the following dependencies:
             * <ul>
             *    <li>org.commonmark:commonmark</li>
             *    <li>org.commonmark:commonmark-ext-yaml-front-matter</li>
             * </ul>
             * This bundle was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             * @deprecated Will be removed in Gradle 9.0.
             */
            @Deprecated
            public Provider<ExternalModuleDependencyBundle> getMarkdown() {
                org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return createBundle("markdown");
            }

            /**
             * Creates a dependency bundle provider for yaml which is an aggregate for the following dependencies:
             * <ul>
             *    <li>org.yaml:snakeyaml</li>
             * </ul>
             * This bundle was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             * @deprecated Will be removed in Gradle 9.0.
             */
            @Deprecated
            public Provider<ExternalModuleDependencyBundle> getYaml() {
                org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return createBundle("yaml");
            }

        /**
         * Returns the group of bundles at bundles.opencv
         * @deprecated Will be removed in Gradle 9.0.
         */
        @Deprecated
        public OpencvBundleAccessors getOpencv() {
            org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
            return baccForOpencvBundleAccessors;
        }

    }

    /**
     * @deprecated Will be removed in Gradle 9.0.
     */
    @Deprecated
    public static class OpencvBundleAccessors extends BundleFactory  implements BundleNotationSupplier{

        public OpencvBundleAccessors(ObjectFactory objects, ProviderFactory providers, DefaultVersionCatalog config, ImmutableAttributesFactory attributesFactory, CapabilityNotationParser capabilityNotationParser) { super(objects, providers, config, attributesFactory, capabilityNotationParser); }

            /**
             * Creates a dependency bundle provider for opencv which is an aggregate for the following dependencies:
             * <ul>
             *    <li>org.bytedeco:javacpp</li>
             *    <li>org.bytedeco:opencv-platform</li>
             * </ul>
             * This bundle was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             * @deprecated Will be removed in Gradle 9.0.
             */
            @Deprecated
            public Provider<ExternalModuleDependencyBundle> asProvider() {
                org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return createBundle("opencv");
            }

            /**
             * Creates a dependency bundle provider for opencv.cuda which is an aggregate for the following dependencies:
             * <ul>
             *    <li>org.bytedeco:javacpp</li>
             *    <li>org.bytedeco:opencv-platform-gpu</li>
             *    <li>org.bytedeco:cuda-platform</li>
             *    <li>org.bytedeco:cuda-platform-redist</li>
             * </ul>
             * This bundle was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             * @deprecated Will be removed in Gradle 9.0.
             */
            @Deprecated
            public Provider<ExternalModuleDependencyBundle> getCuda() {
                org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return createBundle("opencv.cuda");
            }

            /**
             * Creates a dependency bundle provider for opencv.gpu which is an aggregate for the following dependencies:
             * <ul>
             *    <li>org.bytedeco:javacpp</li>
             *    <li>org.bytedeco:opencv-platform-gpu</li>
             *    <li>org.bytedeco:cuda-platform</li>
             * </ul>
             * This bundle was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             * @deprecated Will be removed in Gradle 9.0.
             */
            @Deprecated
            public Provider<ExternalModuleDependencyBundle> getGpu() {
                org.gradle.internal.deprecation.DeprecationLogger.deprecateBehaviour("Accessing libraries or bundles from version catalogs in the plugins block.").withAdvice("Only use versions or plugins from catalogs in the plugins block.").willBeRemovedInGradle9().withUpgradeGuideSection(8, "kotlin_dsl_deprecated_catalogs_plugins_block").nagUser();
                return createBundle("opencv.gpu");
            }

    }

    public static class PluginAccessors extends PluginFactory {
        private final LicensePluginAccessors paccForLicensePluginAccessors = new LicensePluginAccessors(providers, config);

        public PluginAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

            /**
             * Creates a plugin provider for javacpp to the plugin id 'org.bytedeco.gradle-javacpp-platform'
             * with versionRef 'javacppgradle'.
             * This plugin was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<PluginDependency> getJavacpp() { return createPlugin("javacpp"); }

            /**
             * Creates a plugin provider for javafx to the plugin id 'org.openjfx.javafxplugin'
             * with version '0.1.0'.
             * This plugin was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<PluginDependency> getJavafx() { return createPlugin("javafx"); }

            /**
             * Creates a plugin provider for jpackage to the plugin id 'org.beryx.runtime'
             * with version '1.13.0'.
             * This plugin was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<PluginDependency> getJpackage() { return createPlugin("jpackage"); }

        /**
         * Returns the group of plugins at plugins.license
         */
        public LicensePluginAccessors getLicense() {
            return paccForLicensePluginAccessors;
        }

    }

    public static class LicensePluginAccessors extends PluginFactory {

        public LicensePluginAccessors(ProviderFactory providers, DefaultVersionCatalog config) { super(providers, config); }

            /**
             * Creates a plugin provider for license.report to the plugin id 'com.github.jk1.dependency-license-report'
             * with version '2.5'.
             * This plugin was declared in catalog io.github.qupath:qupath-catalog:0.5.0
             */
            public Provider<PluginDependency> getReport() { return createPlugin("license.report"); }

    }

}
