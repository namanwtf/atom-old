package io.github.namanwtf.atom.agent.voice

import android.app.Application
import android.graphics.RuntimeShader
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AtomWaveShaderTest {
    @Test fun effectCompilesWithAndroidShaderCompiler() {
        try {
            RuntimeShader("uniform shader child; half4 main(float2 p) { return child.eval(p); }")
        } catch (error: IllegalArgumentException) {
            // Robolectric native runtime sub-shader support is tracked in upstream issue #9691.
            assumeNoException("Native test runtime lacks child shader support", error)
        }
        val source = RuntimeEnvironment.getApplication().assets.open("assistant/wave.agsl").bufferedReader().use { it.readText() }
        RuntimeShader(source)
    }
}
