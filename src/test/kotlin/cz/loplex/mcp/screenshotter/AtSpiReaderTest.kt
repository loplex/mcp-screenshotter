package cz.loplex.mcp.screenshotter

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AtSpiReaderTest {

    @Test
    fun `test getting UI tree with mock AT-SPI`() {
        var gFreeCalled = false
        var initCalled = false

        val desktopPtr = Memory(8)
        val namePtr = Memory(32).apply { setString(0, "Mock Desktop") }
        val rolePtr = Memory(32).apply { setString(0, "desktop") }

        val atspiMock = object : AtspiLibrary {
            override fun atspi_init(): Int {
                initCalled = true
                return 0
            }
            override fun atspi_get_desktop_count(): Int = 1
            override fun atspi_get_desktop(i: Int): Pointer? = desktopPtr
            override fun atspi_accessible_get_name(obj: Pointer, error: PointerByReference?): Pointer? = namePtr
            override fun atspi_accessible_get_role_name(obj: Pointer, error: PointerByReference?): Pointer? = rolePtr
            override fun atspi_accessible_get_component(obj: Pointer): Pointer? = null
            override fun atspi_accessible_get_child_count(obj: Pointer, error: PointerByReference?): Int = 0
            override fun atspi_accessible_get_child_at_index(obj: Pointer, child_index: Int, error: PointerByReference?): Pointer? = null
            override fun atspi_component_get_extents(obj: Pointer, ctype: Int, error: PointerByReference?): Pointer? = null
        }

        val glibMock = object : GLibLibrary {
            override fun g_free(mem: Pointer?) {
                gFreeCalled = true
            }
        }

        val reader = AtSpiReader(atspiMock, glibMock)
        val tree = reader.getUiTree()

        assertNotNull(tree["desktops"])
        val desktops = tree["desktops"] as List<Map<String, Any>>
        assertEquals(1, desktops.size)
        
        val desktop = desktops[0]
        assertEquals("Mock Desktop", desktop["name"])
        assertEquals("desktop", desktop["role"])

        assertTrue(initCalled, "atspi_init should be called")
        assertTrue(gFreeCalled, "g_free should be called")
    }
}
