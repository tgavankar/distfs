/** @file java-classes.h
    @brief Definition of the inline Java class table.

    The inline Java class table contains pointers to a series of complete class
    files, and the corresponding Java modified UTF-8-encoded class names. Each
    class file is stored directly within the native binary image, and loaded by
    the driver at startup. This removes the need to distribute the classes
    separately from the native driver in a JAR file, and therefore the need to
    somehow communicate the path to the JAR file to the native binary.
 */

#ifndef _JAVA_CLASSES_H_
#define _JAVA_CLASSES_H_

/// Class table entry.
struct class_info
{
    /// Class name, encoded in Java modified UTF-8.
    const char          *name;
    /// Class body. This is the quoted contents of the .class file for the
    /// class.
    const char          *body;
    /// Length, in bytes, of the class body.
    const size_t        body_length;
};

/// Number of classes in the class table.
extern const int        class_count;
/// The class table itself.
extern const class_info classes[];

#endif // #ifndef _JAVA_CLASSES_H_
