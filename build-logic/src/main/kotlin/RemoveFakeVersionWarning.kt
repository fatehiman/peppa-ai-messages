import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * sms-man: removes Fossify Commons' "You are using a fake version of the app" dialog.
 *
 * Fossify Commons shows it when the app id does not start with "org.fossify.": randomly (about
 * 1 in 51 screen opens) and on every 100th app start. "Customize colors" also shows it instead of
 * the color screen when the app id does not contain "fossify" and the app was started more than
 * 100 times. Our app id is com.peppasoft.smsman (an own GPL fork, not a fake), so the library
 * classes are changed at build time:
 * - the methods in [EMPTY_METHODS] get an empty body, so the dialog can never open
 *   (showModdedAppWarning: classic screens; FakeVersionCheck / fakeVersionCheck: Compose screens
 *   using AppTheme);
 * - in BaseSimpleActivity the "contains fossify" test always passes ("yfissof" -> ""), so
 *   "Customize colors" keeps working.
 */
abstract class RemoveFakeVersionWarning : AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor {
        val className = classContext.currentClassData.className
        return if (className == BASE_SIMPLE_ACTIVITY) {
            NoFossifyNameTest(nextClassVisitor)
        } else {
            EmptyMethods(nextClassVisitor, EMPTY_METHODS.getValue(className))
        }
    }

    override fun isInstrumentable(classData: ClassData) =
        classData.className == BASE_SIMPLE_ACTIVITY || classData.className in EMPTY_METHODS

    /** Replaces the body of the given static void methods with a plain `return`. */
    private class EmptyMethods(next: ClassVisitor, private val names: Set<String>) : ClassVisitor(Opcodes.ASM9, next) {
        override fun visitMethod(
            access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?
        ): MethodVisitor? {
            val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
            if (name !in names || mv == null || Type.getReturnType(descriptor) != Type.VOID_TYPE) return mv
            mv.visitCode()
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(0, Type.getArgumentsAndReturnSizes(descriptor) shr 2)
            mv.visitEnd()
            return null // drop the original body
        }
    }

    private class NoFossifyNameTest(next: ClassVisitor) : ClassVisitor(Opcodes.ASM9, next) {
        override fun visitMethod(
            access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?
        ): MethodVisitor? {
            val mv = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
            return object : MethodVisitor(Opcodes.ASM9, mv) {
                override fun visitLdcInsn(value: Any?) {
                    super.visitLdcInsn(if (value == "yfissof") "" else value)
                }
            }
        }
    }

    private companion object {
        const val BASE_SIMPLE_ACTIVITY = "org.fossify.commons.activities.BaseSimpleActivity"
        val EMPTY_METHODS = mapOf(
            "org.fossify.commons.extensions.ActivityKt" to setOf("showModdedAppWarning"),
            "org.fossify.commons.compose.extensions.ActivityExtensionsKt" to setOf("fakeVersionCheck"),
            "org.fossify.commons.compose.extensions.ComposeActivityExtensionsKt" to setOf("FakeVersionCheck"),
        )
    }
}
