#!/usr/bin/env python3
import sys

import gi
gi.require_version('Gtk', '3.0')
from gi.repository import Gtk, Gdk

class SampleApp(Gtk.Window):
    def __init__(self):
        super().__init__(title="MCP GTK3 Sandbox Test")
        self.set_default_size(800, 600)
        self.set_border_width(10)

        # Notebook for Tabs
        notebook = Gtk.Notebook()
        self.add(notebook)

        # ==========================================
        # TAB 1: Main Controls
        # ==========================================
        tab1_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        tab1_box.set_border_width(10)
        
        # --- Left Panel (List) ---
        self.listbox = Gtk.ListBox()
        self.listbox.connect("row-activated", self.on_listbox_activated)
        for item in ["Dashboard", "System Settings", "User Profile", "Advanced", "Logs"]:
            row = Gtk.ListBoxRow()
            label = Gtk.Label(label=item, xalign=0)
            label.set_margin_start(10)
            label.set_margin_end(10)
            label.set_margin_top(5)
            label.set_margin_bottom(5)
            row.add(label)
            self.listbox.add(row)
        
        scrolled_list = Gtk.ScrolledWindow()
        scrolled_list.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        scrolled_list.add(self.listbox)
        scrolled_list.set_size_request(200, -1)
        tab1_box.pack_start(scrolled_list, False, False, 0)

        # --- Right Panel ---
        vbox_right = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        tab1_box.pack_start(vbox_right, True, True, 0)

        # Controls Group
        frame = Gtk.Frame(label="Configuration Settings")
        vbox_right.pack_start(frame, False, False, 0)
        
        controls_vbox = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        controls_vbox.set_border_width(10)
        frame.add(controls_vbox)

        # Volume
        vol_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        vol_label = Gtk.Label(label="Volume:")
        vol_box.pack_start(vol_label, False, False, 0)
        vol_slider = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 0, 100, 1)
        vol_slider.set_value(75)
        vol_box.pack_start(vol_slider, True, True, 0)
        controls_vbox.pack_start(vol_box, False, False, 0)

        # Brightness
        bright_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        bright_label = Gtk.Label(label="Brightness:")
        bright_box.pack_start(bright_label, False, False, 0)
        bright_slider = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 0, 100, 1)
        bright_slider.set_value(50)
        bright_box.pack_start(bright_slider, True, True, 0)
        controls_vbox.pack_start(bright_box, False, False, 0)

        # Mode
        mode_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        mode_label = Gtk.Label(label="Mode:")
        mode_box.pack_start(mode_label, False, False, 0)
        
        radio1 = Gtk.RadioButton.new_with_label_from_widget(None, "Battery Saver")
        radio2 = Gtk.RadioButton.new_with_label_from_widget(radio1, "Performance")
        radio2.set_active(True)
        mode_box.pack_start(radio1, False, False, 0)
        mode_box.pack_start(radio2, False, False, 0)
        controls_vbox.pack_start(mode_box, False, False, 0)

        # Checkbox
        chk_debug = Gtk.CheckButton(label="Enable debug mode")
        controls_vbox.pack_start(chk_debug, False, False, 0)

        # Text Area
        scrolled_text = Gtk.ScrolledWindow()
        scrolled_text.set_hexpand(True)
        scrolled_text.set_vexpand(True)
        self.textview = Gtk.TextView()
        self.textbuffer = self.textview.get_buffer()
        self.textbuffer.set_text("Welcome to the GTK3 GUI version of the testing sandbox!\n\nThis interface contains sliders, radio buttons, checkboxes, and text areas for more robust testing.")
        scrolled_text.add(self.textview)
        vbox_right.pack_start(scrolled_text, True, True, 0)

        # The critical button for E2E tests
        btn_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL)
        self.click_btn = Gtk.Button(label="Click Me!")
        self.click_btn.connect("clicked", self.on_click)
        
        # Give it a specific AT-SPI name/role so it's very easy to find
        self.click_btn.get_accessible().set_name("ClickMeButton")
        
        # Add some color to make it look nice (GTK3 CSS)
        css_provider = Gtk.CssProvider()
        css_provider.load_from_data(b"""
            #target_btn {
                background-image: none;
                background-color: #4CAF50;
                color: white;
                font-weight: bold;
                padding: 10px;
            }
        """)
        self.click_btn.set_name("target_btn")
        context = self.click_btn.get_style_context()
        context.add_provider(css_provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)

        btn_box.pack_end(self.click_btn, False, False, 0)
        vbox_right.pack_start(btn_box, False, False, 0)

        notebook.append_page(tab1_box, Gtk.Label(label="Main Settings"))

        # ==========================================
        # TAB 2: Resizable Panes
        # ==========================================
        # Horizontal paned
        hpaned = Gtk.Paned.new(Gtk.Orientation.HORIZONTAL)
        hpaned.set_border_width(10)
        hpaned.set_position(150) # initial position of horizontal splitter

        # Left side of horizontal pane
        left_text = (
            "LEFT PANE\n\n"
            "Drag the vertical separator to the right to see more of this text.\n\n"
            "Initially, this pane is quite narrow, so long lines will wrap or be cut off.\n\n"
            "Here is a hidden code that you need to uncover by making the pane wider: Code: Alpha-77X\n\n"
            "And at the very bottom, there is another item:\n"
            "Target: Project Delta."
        )
        left_pane_text = Gtk.TextView()
        left_pane_text.set_wrap_mode(Gtk.WrapMode.NONE)
        left_pane_text.get_buffer().set_text(left_text)
        left_scroll = Gtk.ScrolledWindow()
        left_scroll.add(left_pane_text)
        hpaned.pack1(left_scroll, True, False)

        # Right side of horizontal pane - make it a Vertical Paned
        vpaned = Gtk.Paned.new(Gtk.Orientation.VERTICAL)
        vpaned.set_position(200) # initial position of vertical splitter
        hpaned.pack2(vpaned, True, False)

        # Top of vertical pane
        top_text = (
            "TOP RIGHT PANE\n\n"
            "Drag the horizontal separator down to reveal more content here.\n\n"
            "This section contains important configuration details that might be hidden.\n\n"
            "IP Address: 192.168.1.100\n"
            "Subnet Mask: 255.255.255.0\n\n"
            + ("Filler line to take up space and push the gateway down...\n" * 15) +
            "To see the gateway, pull the splitter down!\n\n"
            "Gateway: 192.168.1.1"
        )
        top_pane_text = Gtk.TextView()
        top_pane_text.set_wrap_mode(Gtk.WrapMode.WORD)
        top_pane_text.get_buffer().set_text(top_text)
        top_scroll = Gtk.ScrolledWindow()
        top_scroll.add(top_pane_text)
        vpaned.pack1(top_scroll, True, False)

        # Bottom of vertical pane
        bottom_text = (
            "BOTTOM RIGHT PANE\n\n"
            "Hidden secrets might be revealed if you resize panes correctly!\n\n"
            "You won't be able to read the special keyword at the very bottom unless you "
            "drag the horizontal splitter upward and possibly the vertical splitter to the left.\n\n"
            "The magic word is: 'Banana'.\n\n"
            + ("Keep resizing... this is filler text to hide the secret.\n" * 20) +
            "Almost there, but not quite. The final secret is further down.\n\n"
            "Secret Phrase: 'Omega Protocol'. Make sure this is fully visible!"
        )
        bottom_pane_text = Gtk.TextView()
        bottom_pane_text.set_wrap_mode(Gtk.WrapMode.WORD)
        bottom_pane_text.get_buffer().set_text(bottom_text)
        bottom_scroll = Gtk.ScrolledWindow()
        bottom_scroll.add(bottom_pane_text)
        vpaned.pack2(bottom_scroll, True, False)

        notebook.append_page(hpaned, Gtk.Label(label="Resizable Panes"))

    def on_listbox_activated(self, listbox, row):
        item_text = row.get_child().get_label()
        self.textbuffer.set_text(f"Switched view to: {item_text}\n\nAll settings on this page are now relevant to {item_text}.")

    def on_click(self, widget):
        self.click_btn.set_label("Clicked!")
        self.click_btn.get_accessible().set_name("ClickedButton")
        self.textbuffer.set_text("Button was clicked!\nBackground should change.")
        
        css_provider = Gtk.CssProvider()
        css_provider.load_from_data(b"textview text { background-color: #ffcccc; }")
        self.textview.get_style_context().add_provider(css_provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)

def main():
    app = SampleApp()
    app.connect("destroy", Gtk.main_quit)
    app.show_all()
    Gtk.main()

if __name__ == "__main__":
    main()
