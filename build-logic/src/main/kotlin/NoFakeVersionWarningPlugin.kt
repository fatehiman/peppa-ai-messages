import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/** sms-man: applies [RemoveFakeVersionWarning] to every build variant (debug and release). */
class NoFakeVersionWarningPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.getByType(AndroidComponentsExtension::class.java).onVariants { variant ->
            variant.instrumentation.transformClassesWith(
                RemoveFakeVersionWarning::class.java,
                InstrumentationScope.ALL
            ) {}
            variant.instrumentation.setAsmFramesComputationMode(
                FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
            )
        }
    }
}
