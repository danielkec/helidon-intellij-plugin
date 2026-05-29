// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config

import com.intellij.java.library.JavaLibraryModificationTracker
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import java.io.DataInputStream
import java.io.IOException

private const val OCI_PACKAGE_PREFIX = "com.oracle.helidon.oci"
private const val CONFIG_SOURCE_PROVIDER_SERVICE = "META-INF/services/io.helidon.config.spi.ConfigSourceProvider"
private val CONFIG_METADATA_PATHS = listOf(
  "META-INF/helidon/$HELIDON_CONFIG_METADATA",
  "META-INF/$HELIDON_CONFIG_METADATA",
)

internal data class HelidonOciConfigSourceProviderMetadata(
  val type: String,
  val metadataFiles: List<PsiFile>,
  val dependencyFiles: List<PsiFile>,
)

internal object HelidonOciConfigSourceProviderDiscovery {
  fun getProviderMetadata(module: Module): List<HelidonOciConfigSourceProviderMetadata> {
    return CachedValuesManager.getManager(module.project).getCachedValue(module, OCI_PROVIDER_METADATA_KEY, {
      val psiManager = PsiManager.getInstance(module.project)
      val result = ArrayList<HelidonOciConfigSourceProviderMetadata>()
      val dependencies = ArrayList<PsiFile>()

      for (root in ModuleRootManager.getInstance(module).orderEntries().recursively().classes().roots) {
        val serviceFile = root.findFileByRelativePath(CONFIG_SOURCE_PROVIDER_SERVICE) ?: continue
        val servicePsiFile = psiManager.findFile(serviceFile)
        if (servicePsiFile != null) {
          dependencies.add(servicePsiFile)
        }

        val metadataFiles = CONFIG_METADATA_PATHS.mapNotNull { root.findFileByRelativePath(it) }
          .distinctBy { it.path }
          .mapNotNull { psiManager.findFile(it) }
        dependencies.addAll(metadataFiles)

        val providerTypes = parseServiceFile(serviceFile)
          .filter { it.startsWith(OCI_PACKAGE_PREFIX) }
          .flatMapTo(LinkedHashSet()) { resolveProviderTypes(module, it) }
        for (providerType in providerTypes) {
          result.add(HelidonOciConfigSourceProviderMetadata(providerType, metadataFiles, listOfNotNull(servicePsiFile) + metadataFiles))
        }
      }

      CachedValueProvider.Result.create(result,
                                        JavaLibraryModificationTracker.getInstance(module.project),
                                        ProjectRootModificationTracker.getInstance(module.project),
                                        *dependencies.distinctBy { it.virtualFile.path }.toTypedArray())
    }, false)
  }

  private fun parseServiceFile(serviceFile: com.intellij.openapi.vfs.VirtualFile): List<String> {
    return VfsUtilCore.loadText(serviceFile)
      .lineSequence()
      .map { it.substringBefore('#').trim() }
      .filter { it.isNotEmpty() }
      .toList()
  }

  private fun resolveProviderTypes(module: Module, providerClassName: String): Set<String> {
    val providerClass = JavaPsiFacade.getInstance(module.project)
      .findClass(providerClassName, module.getModuleWithDependenciesAndLibrariesScope(true))
      ?: return emptySet()

    providerClass.findFieldByName("TYPE", false)
      ?.stringConstantValue()
      ?.takeIf(::isOciProviderType)
      ?.let { return setOf(it) }

    val compiledProviderTypes = collectCompiledClassProviderTypes(providerClass)
    if (compiledProviderTypes != null) {
      return compiledProviderTypes
    }

    val result = LinkedHashSet<String>()
    val visitedFields = HashSet<PsiField>()
    for (method in providerClass.methods) {
      if (method.name == "supported" || method.name == "supports") {
        collectStringConstants(method, result, visitedFields)
      }
    }
    return result
  }

  private fun collectStringConstants(element: PsiElement, result: MutableSet<String>, visitedFields: MutableSet<PsiField>) {
    when (element) {
      is PsiLiteralExpression -> {
        (element.value as? String)
          ?.takeIf(::isOciProviderType)
          ?.let(result::add)
      }
      is PsiReferenceExpression -> {
        val field = element.resolve() as? PsiField
        if (field != null) {
          collectStringConstants(field, result, visitedFields)
        }
      }
    }
    for (child in element.children) {
      collectStringConstants(child, result, visitedFields)
    }
  }

  private fun collectStringConstants(field: PsiField, result: MutableSet<String>, visitedFields: MutableSet<PsiField>) {
    if (!visitedFields.add(field)) return

    field.stringConstantValue()
      ?.takeIf(::isOciProviderType)
      ?.let {
        result.add(it)
        return
      }
    field.initializer?.let { collectStringConstants(it, result, visitedFields) }
  }

  private fun PsiField.stringConstantValue(): String? = computeConstantValue() as? String

  private fun collectCompiledClassProviderTypes(providerClass: PsiClass): Set<String>? {
    val classFile = providerClass.containingFile?.virtualFile?.takeIf { it.extension == "class" } ?: return null
    return try {
      classFile.inputStream.use { input ->
        collectClassFileProviderTypes(DataInputStream(input))
      }
    }
    catch (_: IOException) {
      null
    }
  }

  private fun collectClassFileProviderTypes(input: DataInputStream): Set<String> {
    val classFile = readClassFile(input) ?: return emptySet()
    val providerMethods = classFile.methods.filter { it.name == "supported" || it.name == "supports" }
    val providerFields = LinkedHashSet<ClassFieldReference>()
    val result = LinkedHashSet<String>()

    for (method in providerMethods) {
      method.stringConstants(classFile).filterTo(result, ::isOciProviderType)
      method.staticFieldReferences(classFile).forEach(providerFields::add)
    }

    for (fieldReference in providerFields) {
      classFile.findField(fieldReference)
        ?.constantValue
        ?.takeIf(::isOciProviderType)
        ?.let(result::add)
    }

    val staticInitializer = classFile.methods.firstOrNull { it.name == "<clinit>" }
    if (staticInitializer != null && providerFields.isNotEmpty()) {
      val assignments = staticInitializer.stringConstantsAssignedToStaticFields(classFile)
      for (fieldReference in providerFields) {
        assignments[fieldReference]?.filterTo(result, ::isOciProviderType)
      }
    }
    return result
  }

  private fun readClassFile(input: DataInputStream): ParsedClassFile? {
    if (input.readInt() != 0xCAFEBABE.toInt()) return null
    input.skipFully(4) // minor_version, major_version

    val constantPoolCount = input.readUnsignedShort()
    val constantPool = arrayOfNulls<ClassConstant>(constantPoolCount)
    var index = 1
    while (index < constantPoolCount) {
      constantPool[index] = when (input.readUnsignedByte()) {
        1 -> Utf8Constant(input.readUTF())
        7 -> ClassConstantRef(input.readUnsignedShort())
        8 -> StringConstantRef(input.readUnsignedShort())
        9 -> FieldConstantRef(input.readUnsignedShort(), input.readUnsignedShort())
        10, 11 -> {
          input.skipFully(4)
          null
        }
        12 -> NameAndTypeConstant(input.readUnsignedShort(), input.readUnsignedShort())
        3, 4, 17, 18 -> {
          input.skipFully(4)
          null
        }
        5, 6 -> {
          input.skipFully(8)
          index++
          null
        }
        15 -> {
          input.skipFully(3)
          null
        }
        16, 19, 20 -> {
          input.skipFully(2)
          null
        }
        else -> return null
      }
      index++
    }

    input.skipFully(2) // access_flags
    val thisClass = input.readUnsignedShort()
    input.skipFully(2) // super_class
    repeat(input.readUnsignedShort()) {
      input.skipFully(2)
    }

    val fields = ArrayList<ClassField>()
    repeat(input.readUnsignedShort()) {
      input.skipFully(2) // access_flags
      val name = constantPool.utf8(input.readUnsignedShort()) ?: return null
      val descriptor = constantPool.utf8(input.readUnsignedShort()) ?: return null
      var constantValue: String? = null
      repeat(input.readUnsignedShort()) {
        val attributeName = constantPool.utf8(input.readUnsignedShort())
        val attributeLength = input.readUnsignedInt()
        if (attributeName == "ConstantValue" && attributeLength == 2L) {
          constantValue = constantPool.stringConstant(input.readUnsignedShort())
        }
        else {
          input.skipFully(attributeLength)
        }
      }
      fields.add(ClassField(name, descriptor, constantValue))
    }

    val methods = ArrayList<ClassMethod>()
    repeat(input.readUnsignedShort()) {
      input.skipFully(2) // access_flags
      val name = constantPool.utf8(input.readUnsignedShort()) ?: return null
      val descriptor = constantPool.utf8(input.readUnsignedShort()) ?: return null
      var code: ByteArray? = null
      repeat(input.readUnsignedShort()) {
        val attributeName = constantPool.utf8(input.readUnsignedShort())
        val attributeLength = input.readUnsignedInt()
        if (attributeName == "Code") {
          code = input.readCodeAttribute()
        }
        else {
          input.skipFully(attributeLength)
        }
      }
      methods.add(ClassMethod(name, descriptor, code))
    }

    return ParsedClassFile(constantPool.className(thisClass) ?: return null, constantPool, fields, methods)
  }

  private fun isOciProviderType(value: String): Boolean {
    return value.startsWith("oci-") && value.length > "oci-".length
  }

  private fun DataInputStream.readCodeAttribute(): ByteArray {
    skipFully(4) // max_stack, max_locals
    val code = ByteArray(readInt())
    readFully(code)
    repeat(readUnsignedShort()) {
      skipFully(8)
    }
    repeat(readUnsignedShort()) {
      skipFully(2)
      skipFully(readUnsignedInt())
    }
    return code
  }

  private fun ClassMethod.stringConstants(classFile: ParsedClassFile): Set<String> {
    val code = code ?: return emptySet()
    val result = LinkedHashSet<String>()
    scanCode(code) { offset, opcode ->
      when (opcode) {
        0x12 -> classFile.constantPool.stringConstant(code.unsignedByte(offset + 1))?.let(result::add)
        0x13 -> classFile.constantPool.stringConstant(code.unsignedShort(offset + 1))?.let(result::add)
      }
    }
    return result
  }

  private fun ClassMethod.staticFieldReferences(classFile: ParsedClassFile): Set<ClassFieldReference> {
    val code = code ?: return emptySet()
    val result = LinkedHashSet<ClassFieldReference>()
    scanCode(code) { offset, opcode ->
      if (opcode == 0xb2) {
        classFile.constantPool.fieldReference(code.unsignedShort(offset + 1))?.let(result::add)
      }
    }
    return result
  }

  private fun ClassMethod.stringConstantsAssignedToStaticFields(classFile: ParsedClassFile): Map<ClassFieldReference, List<String>> {
    val code = code ?: return emptyMap()
    val result = LinkedHashMap<ClassFieldReference, MutableList<String>>()
    val pendingStrings = ArrayList<String>()
    scanCode(code) { offset, opcode ->
      when (opcode) {
        0x12 -> classFile.constantPool.stringConstant(code.unsignedByte(offset + 1))?.let(pendingStrings::add)
        0x13 -> classFile.constantPool.stringConstant(code.unsignedShort(offset + 1))?.let(pendingStrings::add)
        0xb3 -> {
          val fieldReference = classFile.constantPool.fieldReference(code.unsignedShort(offset + 1))
          if (fieldReference != null && pendingStrings.isNotEmpty()) {
            result.getOrPut(fieldReference) { ArrayList() }.addAll(pendingStrings)
          }
          pendingStrings.clear()
        }
      }
    }
    return result
  }

  private fun scanCode(code: ByteArray, visitor: (offset: Int, opcode: Int) -> Unit) {
    var offset = 0
    while (offset < code.size) {
      val opcode = code.unsignedByte(offset)
      visitor(offset, opcode)
      val nextOffset = offset + instructionLength(code, offset, opcode)
      if (nextOffset <= offset) return
      offset = nextOffset
    }
  }

  private fun instructionLength(code: ByteArray, offset: Int, opcode: Int): Int {
    return when (opcode) {
      0x10, 0x12, in 0x15..0x19, in 0x36..0x3a, 0xa9, 0xbc -> 2
      0x11, 0x13, 0x14, 0x84, in 0x99..0xa8, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8,
      0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7 -> 3
      0xc5 -> 4
      0xb9, 0xba, 0xc8, 0xc9 -> 5
      0xaa -> tableSwitchLength(code, offset)
      0xab -> lookupSwitchLength(code, offset)
      0xc4 -> if (code.unsignedByte(offset + 1) == 0x84) 6 else 4
      else -> 1
    }
  }

  private fun tableSwitchLength(code: ByteArray, offset: Int): Int {
    val padding = switchPadding(offset)
    val header = offset + 1 + padding
    val low = code.int(header + 4)
    val high = code.int(header + 8)
    return 1 + padding + 12 + (high - low + 1) * 4
  }

  private fun lookupSwitchLength(code: ByteArray, offset: Int): Int {
    val padding = switchPadding(offset)
    val header = offset + 1 + padding
    val pairs = code.int(header + 4)
    return 1 + padding + 8 + pairs * 8
  }

  private fun switchPadding(offset: Int): Int = (4 - ((offset + 1) % 4)) % 4

  private fun DataInputStream.readUnsignedInt(): Long = readInt().toLong() and 0xffffffffL

  private fun DataInputStream.skipFully(byteCount: Long) {
    var remaining = byteCount
    while (remaining > 0) {
      val skipped = skip(remaining)
      if (skipped <= 0) {
        readByte()
        remaining--
      }
      else {
        remaining -= skipped
      }
    }
  }

  private fun Array<ClassConstant?>.utf8(index: Int): String? = (getOrNull(index) as? Utf8Constant)?.value

  private fun Array<ClassConstant?>.className(index: Int): String? {
    val classRef = getOrNull(index) as? ClassConstantRef ?: return null
    return utf8(classRef.nameIndex)
  }

  private fun Array<ClassConstant?>.stringConstant(index: Int): String? {
    return when (val constant = getOrNull(index)) {
      is StringConstantRef -> utf8(constant.stringIndex)
      is Utf8Constant -> constant.value
      else -> null
    }
  }

  private fun Array<ClassConstant?>.fieldReference(index: Int): ClassFieldReference? {
    val fieldRef = getOrNull(index) as? FieldConstantRef ?: return null
    val nameAndType = getOrNull(fieldRef.nameAndTypeIndex) as? NameAndTypeConstant ?: return null
    return ClassFieldReference(className(fieldRef.classIndex) ?: return null,
                               utf8(nameAndType.nameIndex) ?: return null,
                               utf8(nameAndType.descriptorIndex) ?: return null)
  }

  private fun ParsedClassFile.findField(reference: ClassFieldReference): ClassField? {
    if (reference.owner != name) return null
    return fields.firstOrNull { it.name == reference.name && it.descriptor == reference.descriptor }
  }

  private fun ByteArray.unsignedByte(offset: Int): Int = getOrNull(offset)?.toInt()?.and(0xff) ?: 0

  private fun ByteArray.unsignedShort(offset: Int): Int = (unsignedByte(offset) shl 8) or unsignedByte(offset + 1)

  private fun ByteArray.int(offset: Int): Int {
    return (unsignedByte(offset) shl 24) or
           (unsignedByte(offset + 1) shl 16) or
           (unsignedByte(offset + 2) shl 8) or
           unsignedByte(offset + 3)
  }

  private data class ParsedClassFile(val name: String,
                                     val constantPool: Array<ClassConstant?>,
                                     val fields: List<ClassField>,
                                     val methods: List<ClassMethod>)

  private data class ClassField(val name: String, val descriptor: String, val constantValue: String?)

  private data class ClassMethod(val name: String, val descriptor: String, val code: ByteArray?)

  private data class ClassFieldReference(val owner: String, val name: String, val descriptor: String)

  private interface ClassConstant

  private data class Utf8Constant(val value: String) : ClassConstant

  private data class ClassConstantRef(val nameIndex: Int) : ClassConstant

  private data class StringConstantRef(val stringIndex: Int) : ClassConstant

  private data class FieldConstantRef(val classIndex: Int, val nameAndTypeIndex: Int) : ClassConstant

  private data class NameAndTypeConstant(val nameIndex: Int, val descriptorIndex: Int) : ClassConstant
}

private val OCI_PROVIDER_METADATA_KEY =
  Key.create<CachedValue<List<HelidonOciConfigSourceProviderMetadata>>>("HELIDON_OCI_CONFIG_SOURCE_PROVIDER_METADATA")
