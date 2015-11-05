/*
 * Copyright 2010-2015 JetBrains s.r.o.
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

package org.jetbrains.kotlin.idea.core

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.stubs.StringStubIndexExtension
import com.intellij.util.Processor
import com.intellij.util.indexing.IdFilter
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.caches.resolve.getJavaFieldDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.getJavaMethodDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.psiClassToDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.resolveImportReference
import org.jetbrains.kotlin.idea.imports.importableFqName
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.idea.stubindex.*
import org.jetbrains.kotlin.idea.util.CallType
import org.jetbrains.kotlin.idea.util.CallTypeAndReceiver
import org.jetbrains.kotlin.idea.util.receiverTypes
import org.jetbrains.kotlin.idea.util.substituteExtensionIfCallable
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.load.java.descriptors.SamAdapterDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.isAnnotatedAsHidden
import org.jetbrains.kotlin.resolve.lazy.ResolveSessionUtils
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.utils.addIfNotNull
import org.jetbrains.kotlin.utils.addToStdlib.singletonOrEmptyList
import java.util.*

public class KotlinIndicesHelper(
        private val resolutionFacade: ResolutionFacade,
        private val scope: GlobalSearchScope,
        visibilityFilter: (DeclarationDescriptor) -> Boolean,
        applyExcludeSettings: Boolean = true,
        private val visibilityFilterMayIncludeAccessible: Boolean = false
) {

    private val moduleDescriptor = resolutionFacade.moduleDescriptor
    private val project = resolutionFacade.project

    private val descriptorFilter: (DeclarationDescriptor) -> Boolean = filter@ {
        if (it.isAnnotatedAsHidden()) return@filter false
        if (!visibilityFilter(it)) return@filter false
        if (applyExcludeSettings && isExcludedFromAutoImport(it)) return@filter false
        true
    }

    public fun getTopLevelCallablesByName(name: String): Collection<CallableDescriptor> {
        val declarations = HashSet<KtCallableDeclaration>()
        declarations.addTopLevelNonExtensionCallablesByName(KotlinFunctionShortNameIndex.getInstance(), name)
        declarations.addTopLevelNonExtensionCallablesByName(KotlinPropertyShortNameIndex.getInstance(), name)
        return declarations
                .flatMap { it.resolveToDescriptorsWithHack() }
                .filter { it.extensionReceiverParameter == null && descriptorFilter(it) }
    }

    private fun MutableSet<KtCallableDeclaration>.addTopLevelNonExtensionCallablesByName(
            index: StringStubIndexExtension<out KtCallableDeclaration>,
            name: String
    ) {
        index.get(name, project, scope).filterTo(this) { it.getParent() is KtFile && it.getReceiverTypeReference() == null }
    }

    public fun getTopLevelCallables(nameFilter: (String) -> Boolean): Collection<CallableDescriptor> {
        return (KotlinTopLevelFunctionFqnNameIndex.getInstance().getAllKeys(project).asSequence() +
                KotlinTopLevelPropertyFqnNameIndex.getInstance().getAllKeys(project).asSequence())
                .map { FqName(it) }
                .filter {
                    ProgressManager.checkCanceled()
                    nameFilter(it.shortName().asString())
                }
                .toSet()
                .flatMap { findTopLevelCallables(it).filter(descriptorFilter) }
    }

    public fun getCallableTopLevelExtensions(
            callTypeAndReceiver: CallTypeAndReceiver<*, *>,
            position: KtExpression,
            bindingContext: BindingContext,
            nameFilter: (String) -> Boolean
    ): Collection<CallableDescriptor> {
        val receiverTypes = callTypeAndReceiver.receiverTypes(bindingContext, position, moduleDescriptor, resolutionFacade, predictableSmartCastsOnly = false)
                            ?: return emptyList()
        return getCallableTopLevelExtensions(callTypeAndReceiver, receiverTypes, nameFilter)
    }

    public fun getCallableTopLevelExtensions(
            callTypeAndReceiver: CallTypeAndReceiver<*, *>,
            receiverTypes: Collection<KotlinType>,
            nameFilter: (String) -> Boolean
    ): Collection<CallableDescriptor> {
        if (receiverTypes.isEmpty()) return emptyList()

        val receiverTypeNames = HashSet<String>()
        receiverTypes.forEach { receiverTypeNames.addTypeNames(it) }

        val index = KotlinTopLevelExtensionsByReceiverTypeIndex.INSTANCE

        val declarations = index.getAllKeys(project)
                .asSequence()
                .filter {
                    ProgressManager.checkCanceled()
                    KotlinTopLevelExtensionsByReceiverTypeIndex.receiverTypeNameFromKey(it) in receiverTypeNames
                        && nameFilter(KotlinTopLevelExtensionsByReceiverTypeIndex.callableNameFromKey(it))
                }
                .flatMap { index.get(it, project, scope).asSequence() }

        return findSuitableExtensions(declarations, receiverTypes, callTypeAndReceiver.callType)
    }

    private fun MutableCollection<String>.addTypeNames(type: KotlinType) {
        val constructor = type.getConstructor()
        addIfNotNull(constructor.getDeclarationDescriptor()?.getName()?.asString())
        constructor.getSupertypes().forEach { addTypeNames(it) }
    }

    /**
     * Check that function or property with the given qualified name can be resolved in given scope and called on given receiver
     */
    private fun findSuitableExtensions(
            declarations: Sequence<KtCallableDeclaration>,
            receiverTypes: Collection<KotlinType>,
            callType: CallType<*>
    ): Collection<CallableDescriptor> {
        val result = LinkedHashSet<CallableDescriptor>()

        fun processDescriptor(descriptor: CallableDescriptor) {
            if (descriptor.extensionReceiverParameter != null && descriptorFilter(descriptor)) {
                result.addAll(descriptor.substituteExtensionIfCallable(receiverTypes, callType))
            }
        }

        declarations.forEach { it.resolveToDescriptorsWithHack().forEach(::processDescriptor) }

        return result
    }

    public fun getJvmClassesByName(name: String): Collection<ClassifierDescriptor>
            = PsiShortNamesCache.getInstance(project).getClassesByName(name, scope)
            .map { resolutionFacade.psiClassToDescriptor(it) }
            .filterNotNull()
            .filter(descriptorFilter)
            .toSet()

    public fun getKotlinClasses(nameFilter: (String) -> Boolean, kindFilter: (ClassKind) -> Boolean): Collection<ClassDescriptor> {
        return KotlinFullClassNameIndex.getInstance().getAllKeys(project).asSequence()
                .map { FqName(it) }
                .filter {
                    ProgressManager.checkCanceled()
                    nameFilter(it.shortName().asString())
                }
                .toList()
                .flatMap { getClassDescriptorsByFQName(it, kindFilter) }
    }

    private fun getClassDescriptorsByFQName(classFQName: FqName, kindFilter: (ClassKind) -> Boolean): Collection<ClassDescriptor> {
        val declarations = KotlinFullClassNameIndex.getInstance()[classFQName.asString(), project, scope]

        if (declarations.isEmpty()) {
            // This fqn is absent in caches, dead or not in scope
            return emptyList()
        }

        // Note: Can't search with psi element as analyzer could be built over temp files
        return ResolveSessionUtils.getClassOrObjectDescriptorsByFqName(moduleDescriptor, classFQName) { kindFilter(it.getKind()) }
                .filter(descriptorFilter)
    }

    public fun getObjectMembers(descriptorKindFilter: DescriptorKindFilter, nameFilter: (String) -> Boolean): Collection<CallableDescriptor> {
        val result = LinkedHashSet<CallableDescriptor>()

        fun addFromIndex(index: StringStubIndexExtension<out KtCallableDeclaration>) {
            for (name in index.getAllKeys(project)) {
                ProgressManager.checkCanceled()
                if (!nameFilter(name)) continue

                for (declaration in index.get(name, project, scope)) {
                    if (declaration.parent.parent !is KtObjectDeclaration) continue
                    if (!visibilityFilterMayIncludeAccessible && declaration.hasModifier(KtTokens.PRIVATE_KEYWORD)) continue
                    declaration.resolveToDescriptorsWithHack().filterTo(result) { descriptorKindFilter.accepts(it) && descriptorFilter(it) }
                }
            }
        }

        if (descriptorKindFilter.kindMask.and(DescriptorKindFilter.FUNCTIONS_MASK) != 0) {
            addFromIndex(KotlinFunctionShortNameIndex.getInstance())
        }
        if (descriptorKindFilter.kindMask.and(DescriptorKindFilter.VARIABLES_MASK) != 0) {
            addFromIndex(KotlinPropertyShortNameIndex.getInstance())
        }

        return result
    }

    public fun getJavaStaticMembers(descriptorKindFilter: DescriptorKindFilter, nameFilter: (String) -> Boolean): Collection<CallableDescriptor> {
        val result = LinkedHashSet<CallableDescriptor>()

        val idFilter = IdFilter.getProjectIdFilter(resolutionFacade.project, false)
        val shortNamesCache = PsiShortNamesCache.getInstance(project)

        val methodNamesProcessor = Processor<String> { name ->
            ProgressManager.checkCanceled()
            if (!nameFilter(name)) return@Processor true

            for (method in shortNamesCache.getMethodsByName(name, scope)) {
                if (!method.hasModifierProperty(PsiModifier.STATIC)) continue
                if (!visibilityFilterMayIncludeAccessible && method.hasModifierProperty(PsiModifier.PRIVATE)) continue
                val descriptor = method.getJavaMethodDescriptor(resolutionFacade) ?: continue
                val container = descriptor.containingDeclaration as? ClassDescriptor ?: continue
                if (descriptorKindFilter.accepts(descriptor) && descriptorFilter(descriptor)) {
                    result.add(descriptor)

                    val samAdapter = container.staticScope.getContributedFunctions(descriptor.name, NoLookupLocation.FROM_IDE)
                            .filterIsInstance<SamAdapterDescriptor<*>>()
                            .firstOrNull { it.originForSam.original == descriptor.original }
                    result.addIfNotNull(samAdapter)
                }
            }
            true
        }
        shortNamesCache.processAllMethodNames(methodNamesProcessor, scope, idFilter)

        val fieldNamesProcessor = Processor<String> { name ->
            ProgressManager.checkCanceled()
            if (!nameFilter(name)) return@Processor true

            for (field in shortNamesCache.getFieldsByName(name, scope)) {
                if (!field.hasModifierProperty(PsiModifier.STATIC)) continue
                if (!visibilityFilterMayIncludeAccessible && field.hasModifierProperty(PsiModifier.PRIVATE)) continue
                val descriptor = field.getJavaFieldDescriptor() ?: continue
                if (descriptorKindFilter.accepts(descriptor) && descriptorFilter(descriptor)) {
                    result.add(descriptor)
                }
            }
            true
        }
        shortNamesCache.processAllFieldNames(fieldNamesProcessor, scope, idFilter)

        return result
    }

    private fun findTopLevelCallables(fqName: FqName): Collection<CallableDescriptor> {
        return resolutionFacade.resolveImportReference(moduleDescriptor, fqName)
                .filterIsInstance<CallableDescriptor>()
                .filter { it.getExtensionReceiverParameter() == null }
    }

    private fun isExcludedFromAutoImport(descriptor: DeclarationDescriptor): Boolean {
        val fqName = descriptor.importableFqName?.asString() ?: return false

        return CodeInsightSettings.getInstance().EXCLUDED_PACKAGES
                .any { excluded -> fqName == excluded || (fqName.startsWith(excluded) && fqName[excluded.length()] == '.') }
    }

    private fun KtCallableDeclaration.resolveToDescriptorsWithHack(): Collection<CallableDescriptor> {
        if (getContainingJetFile().isCompiled()) { //TODO: it's temporary while resolveToDescriptor does not work for compiled declarations
            return resolutionFacade.resolveImportReference(moduleDescriptor, fqName!!).filterIsInstance<CallableDescriptor>()
        }
        else {
            return (resolutionFacade.resolveToDescriptor(this) as? CallableDescriptor).singletonOrEmptyList()
        }
    }
}

