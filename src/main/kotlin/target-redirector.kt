/*

#
#   Target Redirector Burp extension
#
#   Copyright (C) 2016-2018 Paul Taylor
#

# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this output_file except in compliance with the License.
# You may obtain a copy of the License at

# http://www.apache.org/licenses/LICENSE-2.0

# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
#    limitations under the License.

 */

package burp

import java.awt.event.ActionEvent
import java.awt.event.ActionListener

import java.net.InetAddress

import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.JCheckBox
import javax.swing.JOptionPane


class Dns_Json() {

    companion object {

        var backup = ""
        var host_list = arrayListOf<String>()

        fun add(host: String) {

            var config = BurpExtender.cb.saveConfigAsJson(
                "project_options.connections.hostname_resolution"
            )

            host_list.add(host)

            if (backup == "") {               
                backup = config
            } 

            var snippet = "{\"enabled\":true,\"hostname\":\"" +
                                host +
                                "\",\"ip_address\":\"127.0.0.1\"}" +
                                if (config.indexOf("ip_address") >= 0) "," else ""
            config = config.substring(0, 85) + snippet + config.substring(85, config.length)
            BurpExtender.cb.loadConfigFromJson(config)
        }

        fun remove(host: String = "") {

            if (backup != "") {
                BurpExtender.cb.loadConfigFromJson(backup)
            }
            if (host != "") {
                host_list.remove(host)
                for (listed_host in host_list) {
                    add(listed_host)
                }
            }
        }
    }    
}

class Redirector(val id: Int, val view: UI, val host_header: Boolean, val original: Map<String, String>, val replacement: Map<String, String>) {

    companion object {

        var instances = arrayListOf<Redirector>()

        fun add_instance(view: UI, host_header: Boolean, original_data: Map<String, String>, replacement_data: Map<String, String>) {
            val id = instances.size
            instances.add(Redirector(id, view, host_header, original_data, replacement_data))
            view.notification("Initialising new redirector #" + id + " (total " + instances.size + ")", "Redirector")
        }

        fun remove_instance(view: UI, id: Int) {
            instances.removeAt(id)
            view.notification("Removed redirector #" + id + " (new total " + instances.size + ")", "Redirector")
        }

        fun instance_add_or_remove(view: UI, host_header: Boolean, original_data: Map<String, String>, replacement_data: Map<String, String>) : Int {

            if (instances.isEmpty()) {
                add_instance(view, host_header, original_data, replacement_data)
            }

            val instance_id = instances.size - 1
            val instance = instances[instance_id]
            
            if (!instance.toggle()) {
                Redirector.remove_instance(view, instance_id)
                return -1
            } else {
                return instance_id
            }
        }
    }

    var active = false

    fun notification(text: String, popup: Boolean = false) {
        view.notification(text, "Redirector#" + id, popup)
    }

    fun original_url() = "${original["protocol"]}://${original["host"]}:${original["port"]}"
    fun replacement_url() = "${replacement["protocol"]}://${replacement["host"]}:${replacement["port"]}"

    fun getbyname(name: String, popup_on_error: Boolean = false): Boolean {
        try {
            InetAddress.getByName(name)
            return true
        }
        catch (UnknownHostException: Exception) {
            notification("Hostname/IP \"${name}\" appears to be invalid.", popup_on_error)
            return false
        }
    }

    fun toggle_dns_correction() {

        if (active) {
            Dns_Json.remove(original["host"]!!)
            return
        } else if (!getbyname(original["host"]!!, false)) {
            notification("Hostname/IP \"${original["host"]}\" appears to be invalid.\n\n" +
                "An entry will be added to\nProject options / Hostname Resolution\n" +
                "to allow invalid hostname redirection.", true)

            Dns_Json.add(original["host"]!!)

            view.toggle_dns_correction(true)
        } else {
            view.toggle_dns_correction(false)
        }        
    }

    fun activate(): Boolean {

        if (
            !original["host"].isNullOrBlank() &&
            original["port"]?.toIntOrNull() != null &&
            !replacement["host"].isNullOrBlank() &&
            replacement["port"]?.toIntOrNull() != null &&
            getbyname(replacement["host"]!!, true)
            ) {
                toggle_dns_correction()
                listener.toggle_registration()
                return true
        } else {
                return false
        }
    }

    inner class HttpListener() : IHttpListener {

        var registered = false

        fun toggle_registration() {
            if (registered) {
                BurpExtender.cb.removeHttpListener(this)
                notification("Listener removed")
                registered = false
            } else {
                BurpExtender.cb.registerHttpListener(this)
                registered = true
                notification("Listener enabled")
            }
        }

        fun host_header_set(messageInfo: IHttpRequestResponse){

            val host_regex ="^(?i)(Host:)( {0,1})(.*)$".toRegex()

            var old_header_set = false
            var old_host: String?
            var new_host = replacement["host"]
            var new_header = "Host: " + new_host

            var new_headers = mutableListOf<String>()

            var requestInfo = BurpExtender.cb.helpers.analyzeRequest(messageInfo)

            for (header in requestInfo.headers) {
                
                if (old_header_set) {
                    new_headers.add(header)
                    continue
                } else {
                    old_host = host_regex.matchEntire(header)?.groups?.get(3)?.value
                    if (old_host == null) {
                        new_headers.add(header)
                        continue
                    } else {
                        if (old_host == new_host) {
                            notification("Old host header is already set to ${new_host}, no change required")
                            return
                        } else {
                            notification("> Host header changed from ${old_host} to ${new_host}")
                            new_headers.add(new_header)
                            old_header_set = true
                        }
                    }
                }
            }

            if (!old_header_set) {
                notification("> Existing host header not found. New host header set to ${new_host}")
                new_headers.add(1, new_header)
            }

            messageInfo.request = BurpExtender.cb.helpers.buildHttpMessage(
                                        new_headers,
                                        messageInfo.request.copyOfRange(
                                            requestInfo.bodyOffset,
                                            messageInfo.request.size
                                        )
                                    )
        }

        fun perform_redirect(messageInfo: IHttpRequestResponse) {

            notification("> Matching against URL: ${original_url()}")
        
            if (messageInfo.httpService.host == original["host"]
                && messageInfo.httpService.port == original["port"]?.toIntOrNull()
                && (messageInfo.httpService.protocol == original["protocol"])
                ) {
                    messageInfo.httpService = BurpExtender.cb.helpers.buildHttpService(
                        replacement["host"],
                        replacement["port"]!!.toInt(),
                        replacement["protocol"]
                    )
                    notification(
                        "> Target changed from ${original_url()} to ${replacement_url()}"
                    )
                    if (host_header) {
                        host_header_set(messageInfo)
                    }
                } else {
                    notification("> Target not changed to ${replacement_url()}")
                }
        }

        override fun processHttpMessage(
            toolFlag: Int,
            messageIsRequest: Boolean,
            messageInfo: IHttpRequestResponse) {
            if (!active || !registered) { return }

            val current_url = "${messageInfo.httpService.protocol}://${messageInfo.httpService.host}:${messageInfo.httpService.port}"

            if (messageIsRequest) {
                notification("----->")
                notification("> Incoming request to: ${current_url}")
                perform_redirect(messageInfo)
            }
            else {
                notification("<-----")
                notification("< Incoming response from: ${current_url}")
            }
        }
    }

    var listener = HttpListener()

    fun toggle(): Boolean {

        if (active) {
            toggle_dns_correction()
            listener.toggle_registration()
            active = false
        } else {
            if (activate()) {
                active = true
                notification("Redirection Activated for:\n${original_url()}\nto:\n${replacement_url()}", true)
            } else {
                active = false
                notification("Invalid hostname and/or port settings.", true)
            }
        }
        return active
    }
}


class UI() : ITab {

    override public fun getTabCaption() = "Target Redirector"
    override public fun getUiComponent() = mainpanel

    val mainpanel = JPanel()
    val innerpanel = JPanel()

    val subpanel_upper = JPanel()
    val subpanel_lower = JPanel()

    class redirect_panel(val host: String) : JPanel() {

        val label_host = JLabel(host)       
        val text_host = JTextField(20)

        val label_port = JLabel("on port")
        val text_port = JTextField(5)

        val cbox_https = JCheckBox("with HTTPS")  

        init {
            add(label_host)
            add(text_host)
            add(label_port)
            add(text_port)
            add(cbox_https)
            maximumSize = preferredSize
        }

        fun get_data(): Map<String, String> {
            val data = mutableMapOf<String, String>()
            data["host"] = text_host.text
            data["port"] = text_port.text
            data["protocol"] = if (cbox_https.isSelected()) "https" else "http"
            return data
        }

        fun toggle_lock(locked: Boolean) {
            text_host.setEditable(locked)
            text_port.setEditable(locked)
            cbox_https.setEnabled(locked)
        }
    }

    val redirect_panel_original = redirect_panel("for host/IP")
    val redirect_panel_replacement = redirect_panel("to: host/IP")

    fun toggle_active(active: Boolean) {
        redirect_button.text = if (active) "Remove redirection" else "Activate redirection"
        redirect_panel_original.toggle_lock(active.not())
        redirect_panel_replacement.toggle_lock(active.not())
        cbox_hostheader.setEnabled(active.not())
        if (!active) { cbox_dns_correction.setSelected(false) }
    }

    fun toggle_dns_correction(enabled: Boolean) {
        cbox_dns_correction.setSelected(enabled)
    }

    val redirect_panel_options = JPanel()
    val cbox_hostheader = JCheckBox("Also replace HTTP host header")
    val cbox_dns_correction = JCheckBox("Invalid original hostname DNS correction")

    val redirect_button = JButton("Activate Redirection")

    fun redirect_button_pressed() {

        val instance_id = Redirector.instance_add_or_remove(
                this,
                cbox_hostheader.isSelected(),
                redirect_panel_original.get_data(),
                redirect_panel_replacement.get_data()
            )

        toggle_active( 
            if (instance_id > -1) true else false
        )
    }
    
    fun popup(text: String) {
        JOptionPane.showMessageDialog(null, text, "Burp / Target Redirector", JOptionPane.WARNING_MESSAGE)
    }

    fun log(text: String, source: String) {
        BurpExtender.cb.printOutput("[" + source + "] " + text.replace("\n", " "))
    }

    fun notification(text: String, source: String, popup: Boolean = false) {
        if (popup) {
            popup(text)
        }

        log(text, source)
    }

    init {

        mainpanel.layout = BoxLayout(mainpanel, BoxLayout.Y_AXIS)
        mainpanel.border = BorderFactory.createEmptyBorder(20, 20, 20, 20)

        mainpanel.add(innerpanel)
        mainpanel.add(Box.createVerticalGlue())

        innerpanel.layout = BoxLayout(innerpanel, BoxLayout.Y_AXIS)

        innerpanel.add(subpanel_upper)
        innerpanel.add(subpanel_lower)

        subpanel_upper.border = BorderFactory.createTitledBorder("Redirect all Burp Suite connections")
        subpanel_upper.layout = BoxLayout(subpanel_upper, BoxLayout.Y_AXIS)

        subpanel_upper.add(redirect_panel_original)
        subpanel_upper.add(redirect_panel_replacement)
        subpanel_upper.add(redirect_panel_options)

        redirect_panel_options.add(cbox_hostheader)
        redirect_panel_options.add(cbox_dns_correction)

        cbox_hostheader.setEnabled(true)
        cbox_dns_correction.setEnabled(false)

        subpanel_lower.layout = BoxLayout(subpanel_lower, BoxLayout.X_AXIS)

        subpanel_lower.add(Box.createHorizontalGlue())
        subpanel_lower.add(redirect_button)
        subpanel_lower.add(Box.createVerticalGlue())

        subpanel_upper.maximumSize = subpanel_upper.preferredSize
        subpanel_lower.maximumSize = subpanel_lower.preferredSize
        innerpanel.maximumSize = innerpanel.preferredSize
        mainpanel.maximumSize = mainpanel.preferredSize

        redirect_button.addActionListener(
                object : ActionListener {
                    override fun actionPerformed(e: ActionEvent) {
                        if (!true && e.actionCommand == "") {}  // hack to remove compiler warning
                        redirect_button_pressed()                        // about e argument being unused
                    }
                }
        )

        BurpExtender.cb.customizeUiComponent(mainpanel)
    }

}


class BurpExtender : IBurpExtender, IExtensionStateListener {

    companion object {
        lateinit var cb: IBurpExtenderCallbacks
    }

    override fun extensionUnloaded() {
        Dns_Json.remove()
    }

    override fun registerExtenderCallbacks(callbacks: IBurpExtenderCallbacks) {       
        
        cb = callbacks
        val tab = UI()
        
        cb.setExtensionName("Target Redirector")
        cb.registerExtensionStateListener(this)
        cb.addSuiteTab(tab)
        cb.printOutput("Target Redirector extension loaded")
    }
}
