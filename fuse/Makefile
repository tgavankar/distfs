# Output filename.

OUTPUT = p1-fuse

# C++ source and object files.

CMODULES = java fuse-operations main
COBJECTS = $(foreach module,$(CMODULES),$(module).o)
CSOURCEDIR = native/src
CBINARYDIR = native/bin

# Java source and class files.

JAVAMODULES = fuse/Fuse
JAVASOURCES = $(foreach module,$(JAVAMODULES),$(module).java)
JAVASOURCEDIR = java/src
JAVABINARYDIR = java/bin

# Main filesystem development directory. Replace FSDIR with the correct path if
# this Makefile is not located in a subdirectory of that directory. In
# FSMODULES, list any additional classes from the main project that need to be
# included in a minimal filesystem client.

FSDIR = ..
FSMODULES = common/Path rmi/Stub rmi/RMIException naming/Service \
			naming/NamingStubs storage/Storage
FSCLASSES = $(foreach module,$(FSMODULES),$(FSDIR)/$(module).class)

# Build tool. The build tool reads all the class files that are necessary for
# the client, quotes them as C++ source, and arranges them in a rudimentary
# table. The classes are then loaded at runtime from memory by the JVM.

MAKECLASSESIMAGE = build/MakeClassesImage.class
MAKEIMAGECOMMAND = java build.MakeClassesImage
CLASSESIMAGE = native/bin/java-classes.o
CLASSESIMAGESOURCE = $(CLASSESIMAGE:.o=.cpp)

# Flags for compiling and linking - Mac version.

CPPFLAGS = `pkg-config fuse --cflags` \
	-I /System/Library/Frameworks/JavaVM.framework/Headers -Wall -Werror
LDFLAGS = `pkg-config fuse --libs` -framework JavaVM

# Flags for compiling and linking - Linux version. Uncomment this and remove the
# Mac version if you are on a Linux system.
#CPPFLAGS = `pkg-config fuse --cflags` -I /usr/lib/jvm/default-java/include \
#	-Wall -Werror
#LDFLAGS = `pkg-config fuse --libs` \
#	-L/usr/lib/jvm/default-java/jre/lib/i386/client -ljvm

# Documentation.

JAVADOCDIR = javadoc
DOCLINK = http://download.oracle.com/javase/6/docs/api
DOXYGENDIR = doxygen

# Default locations for files of various types.

vpath %.cpp $(CSOURCEDIR)
vpath %.o $(CBINARYDIR)
vpath %.d $(CBINARYDIR)
vpath %.java $(JAVASOURCEDIR)

.PHONY : docs clean

# Link all object files. $(CLASSESIMAGE) includes the data from Java .class
# files as well.
$(OUTPUT) : $(COBJECTS) $(CLASSESIMAGE)
	g++ $(LDFLAGS) -o $(OUTPUT) \
		$(foreach object,$^,$(CBINARYDIR)/$(notdir $(object)))

# Compile the class images source file.
$(CLASSESIMAGE) : $(CLASSESIMAGESOURCE) $(CSOURCEDIR)/java-classes.h
	g++ -I $(CSOURCEDIR) -Wall -Werror -o $@ -c $<

# Generate the class images source file. Note that this rule, as written,
# depends on a one-to-one correspondence between driver .java files and .class
# files. This might not be the case if one .java file defines a class with inner
# classes - to handle such a case, the rule must be modified.
$(CLASSESIMAGESOURCE) : $(MAKECLASSESIMAGE) $(JAVASOURCES) $(FSCLASSES)
	rm -rf $(JAVABINARYDIR)/*
	javac -cp .:$(FSDIR) -sourcepath $(JAVASOURCEDIR) -d $(JAVABINARYDIR) \
		$(foreach file,$(JAVASOURCES),$(JAVASOURCEDIR)/$(file))
	$(MAKEIMAGECOMMAND) \
		$(foreach mod,$(JAVAMODULES),'$(mod)' '$(JAVABINARYDIR)/$(mod).class') \
		$(foreach mod,$(FSMODULES),'$(mod)' '$(FSDIR)/$(mod).class') > $@

# Compile main filesystem classes.
$(FSCLASSES) : $(FSDIR)/*/*.java
	make -C $(FSDIR) all-classes

# Remove all generated files.
clean :
	rm -rf $(OUTPUT) $(CBINARYDIR)/* $(JAVABINARYDIR)/* build/*.class \
		$(JAVADOCDIR) $(DOXYGENDIR)

# Compile C++ files.
%.o : %.cpp %.d
	g++ $(CPPFLAGS) -o $(CBINARYDIR)/$@ -c $<

# Compile the build tool.
$(MAKECLASSESIMAGE) : $(MAKECLASSESIMAGE:.class=.java)
	javac $<

# Generate documentation.
.PHONY : docs
docs :
	javadoc -link $(DOCLINK) -private -sourcepath $(JAVASOURCEDIR):$(FSDIR) \
		-d $(JAVADOCDIR) -windowtitle "Project 1 FUSE Driver" -nodeprecated \
		-noindex -nohelp -notree -nonavbar fuse
	doxygen

# Generate C++ dependency files.
$(CBINARYDIR)/%.d : %.cpp
	@set -e; rm -f $@; \
	g++ $(CPPFLAGS) -MM $< > $@.$$$$; \
	sed 's,\($*\)\.o[ :]*,\1.o $@ : ,g' < $@.$$$$ > $@; \
	rm -f $@.$$$$

# Incl3ude dependency files.
-include $(foreach module,$(CMODULES),$(CBINARYDIR)/$(module).d)
