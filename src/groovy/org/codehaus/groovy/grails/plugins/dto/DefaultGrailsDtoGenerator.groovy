package org.codehaus.groovy.grails.plugins.dto

import org.codehaus.groovy.grails.commons.GrailsDomainClass

class DefaultGrailsDtoGenerator {
    private final String eol

    private processed

    Map packageTransforms = [:]

    DefaultGrailsDtoGenerator() {
        this(true)
    }

    DefaultGrailsDtoGenerator(boolean useNativeEol) {
        if (useNativeEol) eol = System.getProperty("line.separator")
        else eol = "\n"
    }

    void generate(GrailsDomainClass dc, File rootDir, boolean recursive) {
        processed = [] as Set
        generateInternal(dc, rootDir, recursive)
    }

    Set generateNoRecurse(GrailsDomainClass dc, Writer writer) {
        final dcPkg = dc.clazz.package?.name
        return generateNoRecurseInternal(dc, writer, getTargetPackage(dcPkg))
    }

    private void generateInternal(GrailsDomainClass dc, File rootDir, boolean recursive) {
        final targetPkg = getTargetPackage(dc.clazz.package?.name)
        def dtoFile = getDtoFile(rootDir, dc, targetPkg)
        dtoFile.parentFile.mkdirs()

        def writer = new BufferedWriter(new FileWriter(dtoFile))
        try {
            def relations = generateNoRecurseInternal(dc, writer, targetPkg)
            processed << dc.clazz

            if (recursive && relations) {
                relations.each { rel ->
                    if (!processed.contains(rel.clazz)) {
                        generateInternal(rel, rootDir, true)
                    }
                }
            }
        }
        finally {
            if (writer) writer.close()
        }
    }

    private Set generateNoRecurseInternal(GrailsDomainClass dc, Writer writer, String targetPkg) {
        // Deal with the persistent properties.
        def imports = []
        def fields = []
        def relations = [] as Set
        def processProperty = { prop ->
            def propType = prop.type
            def field = [ name: prop.name ]

            if (prop.referencedPropertyType == propType) {
                field["typeString"] = propType.simpleName + (prop.association ? "DTO" : "")
                addImportIfNecessary(imports, targetPkg, propType, prop.association)
            }
            else {
                field["typeString"] = propType.simpleName + '<' + prop.referencedPropertyType.simpleName + (prop.association ? "DTO" : "") + '>'
                addImportIfNecessary(imports, targetPkg, propType, false)
                addImportIfNecessary(imports, targetPkg, prop.referencedPropertyType, prop.association)
            }

            // Store the reference domain class if this property is
            // an association. This is so that we can return a set
            // of related domain classes.
            if (prop.association) relations << prop.referencedDomainClass

            fields << field
        }

        processProperty.call(dc.identifier)
        dc.persistentProperties.each(processProperty)

        // Start with the package line.
        def out = new PrintWriter(writer)
        out.println "package ${targetPkg};"
        out.println()

        // Now add any required imports.
        if (imports) {
            imports.unique().sort().each { str ->
                out.println "import ${str};"
            }
            out.println()
        }

        // Next, the class declaration.
        out.println "public class ${dc.shortName}DTO implements grails.plugins.dto.DTO {"
        
        // A serialUID, since DTOs are serialisable.
        out.println "    private static final long serialVersionUID = 1L;"
        out.println()

        // The private fields.
        fields.each { field ->
            out.println "    private ${field.typeString} ${field.name};"
        }

        // The getters and setters.
        out.println()
        fields.each { field ->
            def propSuffix = new StringBuilder(field.name)
            propSuffix.setCharAt(0, Character.toUpperCase(propSuffix.charAt(0)))
            propSuffix = propSuffix.toString()

            out.println "    public ${field.typeString} get${propSuffix}() { return ${field.name}; }"
            out.println "    public void set${propSuffix}(${field.typeString} ${field.name}) { this.${field.name} = ${field.name}; }"
        }

        // toString()
        out.println()
        out.println "    public String toString() {"
        out.println "        StringBuilder sb = new StringBuilder();"
        out.println "        sb.append(\"${dc.shortName}DTO[\");"
        fields.each { field ->
            out.println "        sb.append(\"\\n\\t${field.name}: \" + this.${field.name});"
        }
        out.println "        sb.append(\"]\");"
        out.println "        return sb.toString();"
        out.println "    }"

        // Class terminator.
        out.println "}"

        // All done. Make sure all data has been pushed to the destination
        // before we leave.
        writer.flush()

        return relations
    }

    protected void addImportIfNecessary(List imports, String hostPackage, Class clazz, boolean isAssociation) {
        def pkg = isAssociation ? getTargetPackage(clazz.package?.name) : clazz.package?.name
        if (pkg && pkg != hostPackage && pkg != "java.lang") {
            imports << "${pkg}.${clazz.simpleName}${isAssociation ? 'DTO' : ''}"
        }
    }

    protected File getDtoFile(File rootDir, GrailsDomainClass dc, String targetPkg) {
        def pkgPath = targetPkg.replace(".", "/")
        return new File(rootDir, "${pkgPath}/${dc.shortName}DTO.java")
    }

    protected String getTargetPackage(final String dcPkg) {
        def targetPkg = dcPkg
        if (packageTransforms) {
            // Find a transform that matches the domain class package.
            def entry = packageTransforms.find { key, val -> dcPkg?.startsWith(key) }
            if (entry) {
                // Found one, so use the associated package name as the
                // target package.
                targetPkg = dcPkg.replace(entry.key, entry.value)
            }
            else if (packageTransforms["*"]) {
                // Didn't find a matching transform, but did find the
                // wildcard one.
                targetPkg = packageTransforms["*"]
            }
        }

        return targetPkg
    }
}
