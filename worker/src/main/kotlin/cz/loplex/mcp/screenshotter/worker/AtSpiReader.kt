package cz.loplex.mcp.screenshotter.worker

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference

interface AtspiLibrary : Library {
    companion object {
        val INSTANCE: AtspiLibrary by lazy { Native.load("atspi", AtspiLibrary::class.java) }
    }
    fun atspi_init(): Int
    fun atspi_get_desktop_count(): Int
    fun atspi_get_desktop(i: Int): Pointer?
    fun atspi_accessible_get_child_count(obj: Pointer, error: PointerByReference?): Int
    fun atspi_accessible_get_child_at_index(obj: Pointer, child_index: Int, error: PointerByReference?): Pointer?
    fun atspi_accessible_get_name(obj: Pointer, error: PointerByReference?): Pointer?
    fun atspi_accessible_get_role_name(obj: Pointer, error: PointerByReference?): Pointer?
    fun atspi_accessible_get_component(obj: Pointer): Pointer?
    fun atspi_component_get_extents(obj: Pointer, ctype: Int, error: PointerByReference?): Pointer?
}

interface GLibLibrary : Library {
    companion object {
        val INSTANCE: GLibLibrary by lazy { Native.load("glib-2.0", GLibLibrary::class.java) }
    }
    fun g_free(mem: Pointer?)
}

@Structure.FieldOrder("x", "y", "width", "height")
class AtspiRect(p: Pointer? = null) : Structure(p) {
    @JvmField var x: Int = 0
    @JvmField var y: Int = 0
    @JvmField var width: Int = 0
    @JvmField var height: Int = 0

    init {
        if (p != null) {
            read()
        }
    }
}

class AtSpiReader(
    private val atspi: AtspiLibrary = AtspiLibrary.INSTANCE,
    private val glib: GLibLibrary = GLibLibrary.INSTANCE
) {
    private var initialized = false

    fun init() {
        if (!initialized) {
            val res = atspi.atspi_init()
            debugLog("[AtSpiReader] atspi_init() returned: $res")
            initialized = true
        }
    }

    fun getUiTree(): Map<String, Any> {
        init()
        val desktopCount = atspi.atspi_get_desktop_count()
        debugLog("[AtSpiReader] atspi_get_desktop_count() returned: $desktopCount")

        val desktops = mutableListOf<Map<String, Any>>()
        for (i in 0 until desktopCount) {
            val desktop = atspi.atspi_get_desktop(i)
            debugLog("[AtSpiReader] Desktop $i: pointer=$desktop")
            if (desktop != null) {
                val childCount = atspi.atspi_accessible_get_child_count(desktop, null)
                debugLog("[AtSpiReader] Desktop $i child count: $childCount")
                desktops.add(parseAccessible(desktop, 0, 10)) // Max depth 10
            }
        }
        return mapOf("desktops" to desktops)
    }

    private fun parseAccessible(obj: Pointer, currentDepth: Int, maxDepth: Int): Map<String, Any> {
        val result = mutableMapOf<String, Any>()

        val namePtr = atspi.atspi_accessible_get_name(obj, null)
        if (namePtr != null) {
            result["name"] = namePtr.getString(0)
            glib.g_free(namePtr)
        }

        val rolePtr = atspi.atspi_accessible_get_role_name(obj, null)
        if (rolePtr != null) {
            result["role"] = rolePtr.getString(0)
            glib.g_free(rolePtr)
        }

        val componentPtr = atspi.atspi_accessible_get_component(obj)
        if (componentPtr != null) {
            // ATSPI_COORD_TYPE_SCREEN = 0
            val rectPtr = atspi.atspi_component_get_extents(componentPtr, 0, null)
            if (rectPtr != null) {
                val rect = AtspiRect(rectPtr)
                result["rect"] = mapOf("x" to rect.x, "y" to rect.y, "width" to rect.width, "height" to rect.height)
                glib.g_free(rectPtr)
            }
        }

        if (currentDepth < maxDepth) {
            val childCount = atspi.atspi_accessible_get_child_count(obj, null)
            val children = mutableListOf<Map<String, Any>>()
            for (i in 0 until childCount) {
                val child = atspi.atspi_accessible_get_child_at_index(obj, i, null)
                if (child != null) {
                    children.add(parseAccessible(child, currentDepth + 1, maxDepth))
                }
            }
            if (children.isNotEmpty()) {
                result["children"] = children
            }
        }

        return result
    }
}
