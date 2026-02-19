package com.bogdanbujor.azuretemplates.ui

import com.bogdanbujor.azuretemplates.core.GraphBuilder
import com.bogdanbujor.azuretemplates.core.GraphData
import com.bogdanbujor.azuretemplates.settings.PluginSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.serialization.json.*
import org.cef.browser.CefBrowser
import org.cef.handler.CefLoadHandlerAdapter
import javax.swing.JPanel
import java.awt.BorderLayout

/**
 * Tool window that embeds the D3 force-directed graph in a JBCefBrowser.
 *
 * Port of graphWebViewProvider.js from the VS Code extension.
 * Reuses media/graph.js, media/d3.min.js, and generates graph.html.
 */
class GraphToolWindow : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = GraphPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

class GraphPanel(private val project: Project) {

    val component: JPanel
    private val browser: JBCefBrowser
    private val jsQuery: JBCefJSQuery
    private var fileScope: Boolean = false

    init {
        browser = JBCefBrowser()
        jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

        // Handle messages from JavaScript
        jsQuery.addHandler { jsonMsg ->
            handleMessage(jsonMsg)
            null
        }

        component = JPanel(BorderLayout())
        component.add(browser.component, BorderLayout.CENTER)

        // Inject bridge after page loads
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: org.cef.browser.CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    injectBridge()
                    loadGraphData()
                }
            }
        }, browser.cefBrowser)

        // Load the HTML
        loadHtml()

        // Listen for editor changes
        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) {
                    if (fileScope) {
                        loadGraphData()
                    }
                }
            }
        )
    }

    private fun loadHtml() {
        val d3Js = loadResource("/media/d3.min.js")

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    html, body { margin: 0; padding: 0; width: 100%; height: 100%; overflow: hidden; background: #1e1e1e; color: #ccc; font-family: sans-serif; }
                    svg { position: absolute; top: 0; left: 0; width: 100%; height: 100%; }
                    .node circle { stroke: #fff; stroke-width: 1.5px; cursor: pointer; }
                    .node text { font-size: 10px; fill: #ccc; pointer-events: none; }
                    .link-label { font-size: 8px; fill: #888; }
                    #controls { position: absolute; top: 10px; right: 10px; z-index: 10; display: flex; gap: 4px; }
                    #controls button { padding: 4px 8px; background: #333; color: #ccc; border: 1px solid #555; cursor: pointer; border-radius: 3px; font-size: 12px; }
                    #controls button:hover { background: #444; }
                    #search { position: absolute; top: 10px; left: 10px; z-index: 10; }
                    #search input { padding: 4px 8px; background: #333; color: #ccc; border: 1px solid #555; border-radius: 3px; }
                    .node.pipeline circle { fill: #4CAF50; }
                    .node.local circle { fill: #2196F3; }
                    .node.external circle { fill: #FF9800; }
                    .node.missing circle { fill: #f44336; }
                    .node.unknown circle { fill: #9E9E9E; }
                    .node.scope circle { stroke: #FFD700; stroke-width: 3px; }
                    #legend { position: absolute; bottom: 10px; left: 10px; z-index: 10; }
                    #legend-toggle { padding: 4px 10px; background: #333; color: #ccc; border: 1px solid #555; cursor: pointer; border-radius: 4px; font-size: 12px; display: flex; align-items: center; gap: 4px; }
                    #legend-toggle:hover { background: #444; }
                    #legend-toggle .arrow { font-size: 9px; transition: transform 0.2s; }
                    #legend-toggle.open .arrow { transform: rotate(90deg); }
                    #legend-content { display: none; background: rgba(30,30,30,0.95); border: 1px solid #555; border-radius: 4px; padding: 8px 12px; margin-top: 4px; font-size: 11px; }
                    #legend-content.open { display: block; }
                    #legend-content div { margin: 2px 0; display: flex; align-items: center; gap: 6px; }
                    #legend .swatch { width: 10px; height: 10px; border-radius: 50%; display: inline-block; }
                    #legend .line-swatch { width: 20px; height: 0; display: inline-block; border-top: 2px solid; }
                </style>
            </head>
            <body>
                <div id="search"><input type="text" id="searchInput" placeholder="Search nodes..." /></div>
                <div id="controls">
                    <button onclick="zoomIn()" title="Zoom In">+</button>
                    <button onclick="zoomOut()" title="Zoom Out">−</button>
                    <button onclick="fitGraph()">Fit</button>
                    <button onclick="resetGraph()">Reset</button>
                    <button onclick="toggleScope()">Toggle Scope</button>
                </div>
                <div id="legend">
                    <button id="legend-toggle" onclick="document.getElementById('legend-toggle').classList.toggle('open'); document.getElementById('legend-content').classList.toggle('open');">
                        <span class="arrow">▶</span> Legend
                    </button>
                    <div id="legend-content">
                        <div><span class="swatch" style="background:#4CAF50"></span> Pipeline root</div>
                        <div><span class="swatch" style="background:#2196F3"></span> Local template</div>
                        <div><span class="swatch" style="background:#FF9800"></span> External template</div>
                        <div><span class="swatch" style="background:#f44336"></span> Missing file</div>
                        <div style="margin-top:4px"><span class="line-swatch" style="border-color:#4e9de0"></span> Calls (downstream) →</div>
                        <div><span class="line-swatch" style="border-color:#e09a3d; border-top-style:dashed"></span> Called by (upstream) ←</div>
                    </div>
                </div>
                <svg id="graph">
                    <defs>
                        <marker id="arrow" viewBox="0 0 10 6" refX="18" refY="3"
                                markerWidth="8" markerHeight="6" orient="auto-start-reverse">
                            <path d="M0,0 L10,3 L0,6 Z" fill="#4e9de0"/>
                        </marker>
                        <marker id="arrow-upstream" viewBox="0 0 10 6" refX="18" refY="3"
                                markerWidth="8" markerHeight="6" orient="auto-start-reverse">
                            <path d="M0,0 L10,3 L0,6 Z" fill="#e09a3d"/>
                        </marker>
                        <marker id="arrow-default" viewBox="0 0 10 6" refX="18" refY="3"
                                markerWidth="8" markerHeight="6" orient="auto-start-reverse">
                            <path d="M0,0 L10,3 L0,6 Z" fill="#888"/>
                        </marker>
                    </defs>
                </svg>
                <script>$d3Js</script>
                <script>
                    // Bridge to IntelliJ
                    const bridge = {
                        postMessage(msg) {
                            if (window.__intellijBridge) {
                                window.__intellijBridge(JSON.stringify(msg));
                            }
                        }
                    };
                    
                    let graphData = { nodes: [], edges: [] };
                    let simulation;
                    let svg, g, link, node, linkLabel;
                    let zoom;
                    let fileScopeMode = false;
                    
                    // Track drag distance to distinguish click from drag
                    let dragStartX = 0, dragStartY = 0, dragMoved = false;
                    
                    function edgeColor(e) {
                        if (e.direction === 'upstream')   return '#e09a3d';
                        if (e.direction === 'downstream') return '#4e9de0';
                        return '#888';
                    }
                    
                    function edgeMarker(e) {
                        if (e.direction === 'upstream')   return 'url(#arrow-upstream)';
                        if (e.direction === 'downstream') return 'url(#arrow)';
                        return 'url(#arrow-default)';
                    }
                    
                    function initGraph() {
                        svg = d3.select('#graph');
                        const width = window.innerWidth;
                        const height = window.innerHeight;
                        
                        zoom = d3.zoom()
                            .scaleExtent([0.1, 4])
                            .on('zoom', (event) => {
                                g.attr('transform', event.transform);
                            });
                        
                        svg.call(zoom);
                        
                        // Disable default double-click zoom (only zooms in, confusing)
                        svg.on('dblclick.zoom', null);
                        
                        // Explicit wheel zoom fallback for JBCef
                        document.getElementById('graph').addEventListener('wheel', function(e) {
                            e.preventDefault();
                            const currentTransform = d3.zoomTransform(svg.node());
                            const scaleFactor = e.deltaY < 0 ? 1.1 : 0.9;
                            const newScale = Math.max(0.1, Math.min(4, currentTransform.k * scaleFactor));
                            
                            // Zoom toward mouse position
                            const rect = this.getBoundingClientRect();
                            const mouseX = e.clientX - rect.left;
                            const mouseY = e.clientY - rect.top;
                            
                            const newTransform = d3.zoomIdentity
                                .translate(mouseX, mouseY)
                                .scale(newScale)
                                .translate(-(mouseX - currentTransform.x) / currentTransform.k, -(mouseY - currentTransform.y) / currentTransform.k);
                            
                            svg.call(zoom.transform, newTransform);
                        }, { passive: false });
                        g = svg.append('g');
                        
                        link = g.append('g').attr('class', 'links').selectAll('line');
                        linkLabel = g.append('g').attr('class', 'link-labels').selectAll('text');
                        node = g.append('g').attr('class', 'nodes').selectAll('g');
                        
                        simulation = d3.forceSimulation()
                            .force('link', d3.forceLink().id(d => d.id).distance(120))
                            .force('charge', d3.forceManyBody().strength(-300))
                            .force('center', d3.forceCenter(width / 2, height / 2))
                            .force('collision', d3.forceCollide().radius(30));
                        
                        // Search
                        d3.select('#searchInput').on('input', function() {
                            const query = this.value.toLowerCase();
                            node.style('opacity', d => {
                                if (!query) return 1;
                                return (d.label || '').toLowerCase().includes(query) ? 1 : 0.2;
                            });
                        });
                        
                        window.addEventListener('resize', () => {
                            simulation.force('center', d3.forceCenter(window.innerWidth / 2, window.innerHeight / 2));
                            simulation.alpha(0.3).restart();
                        });
                    }
                    
                    function updateGraph(data) {
                        graphData = data;
                        const nodes = data.nodes || [];
                        const edges = (data.edges || []).map(e => ({ ...e, source: e.source, target: e.target }));
                        
                        // Update links with directional styling
                        link = link.data(edges, d => d.source + '-' + d.target);
                        link.exit().remove();
                        const linkEnter = link.enter().append('line')
                            .attr('stroke', d => edgeColor(d))
                            .attr('stroke-opacity', d => (d.direction === 'upstream' || d.direction === 'downstream') ? 0.8 : 0.6)
                            .attr('stroke-width', d => (d.direction === 'upstream' || d.direction === 'downstream') ? 2 : 1.5)
                            .attr('stroke-dasharray', d => d.direction === 'upstream' ? '5,3' : null)
                            .attr('marker-end', d => edgeMarker(d));
                        link = linkEnter.merge(link);
                        
                        // Update link labels
                        linkLabel = linkLabel.data(edges.filter(e => e.label), d => d.source + '-' + d.target);
                        linkLabel.exit().remove();
                        linkLabel = linkLabel.enter().append('text').attr('class', 'link-label')
                            .attr('fill', d => edgeColor(d))
                            .text(d => d.label)
                            .merge(linkLabel);
                        
                        // Update nodes
                        node = node.data(nodes, d => d.id);
                        node.exit().remove();
                        const nodeEnter = node.enter().append('g')
                            .attr('class', d => 'node ' + (d.kind || 'local') + (d.isScope ? ' scope' : ''))
                            .call(d3.drag()
                                .on('start', dragstarted)
                                .on('drag', dragged)
                                .on('end', dragended));
                        
                        nodeEnter.append('circle')
                            .attr('r', d => d.isScope ? 12 : 8);
                        
                        nodeEnter.append('text')
                            .attr('dx', 12)
                            .attr('dy', '.35em')
                            .text(d => d.label + (d.paramCount > 0 ? ' (' + d.paramCount + ')' : ''));
                        
                        nodeEnter.append('title').text(d => d.relativePath || d.label);
                        
                        node = nodeEnter.merge(node);
                        
                        simulation.nodes(nodes).on('tick', ticked);
                        simulation.force('link').links(edges);
                        simulation.alpha(1).restart();
                    }
                    
                    function ticked() {
                        link.attr('x1', d => d.source.x).attr('y1', d => d.source.y)
                            .attr('x2', d => d.target.x).attr('y2', d => d.target.y);
                        linkLabel.attr('x', d => (d.source.x + d.target.x) / 2)
                            .attr('y', d => (d.source.y + d.target.y) / 2);
                        node.attr('transform', d => 'translate(' + d.x + ',' + d.y + ')');
                    }
                    
                    function dragstarted(event, d) {
                        if (!event.active) simulation.alphaTarget(0.3).restart();
                        d.fx = d.x; d.fy = d.y;
                        dragStartX = event.x; dragStartY = event.y; dragMoved = false;
                    }
                    function dragged(event, d) {
                        d.fx = event.x; d.fy = event.y;
                        const dx = event.x - dragStartX, dy = event.y - dragStartY;
                        if (dx * dx + dy * dy > 9) dragMoved = true;
                    }
                    function dragended(event, d) {
                        if (!event.active) simulation.alphaTarget(0);
                        d.fx = null; d.fy = null;
                        // If the mouse barely moved, treat it as a click
                        if (!dragMoved && d.filePath) {
                            bridge.postMessage({ type: 'openFile', filePath: d.filePath });
                        }
                    }
                    
                    function zoomIn() {
                        const currentTransform = d3.zoomTransform(svg.node());
                        const newScale = Math.min(4, currentTransform.k * 1.3);
                        svg.transition().duration(300).call(zoom.scaleTo, newScale);
                    }
                    
                    function zoomOut() {
                        const currentTransform = d3.zoomTransform(svg.node());
                        const newScale = Math.max(0.1, currentTransform.k / 1.3);
                        svg.transition().duration(300).call(zoom.scaleTo, newScale);
                    }
                    
                    function fitGraph() {
                        const bounds = g.node().getBBox();
                        const width = window.innerWidth;
                        const height = window.innerHeight;
                        const scale = Math.min(width / bounds.width, height / bounds.height) * 0.8;
                        const tx = width / 2 - (bounds.x + bounds.width / 2) * scale;
                        const ty = height / 2 - (bounds.y + bounds.height / 2) * scale;
                        svg.transition().duration(500).call(zoom.transform, d3.zoomIdentity.translate(tx, ty).scale(scale));
                    }
                    
                    function resetGraph() {
                        svg.transition().duration(500).call(zoom.transform, d3.zoomIdentity);
                        simulation.alpha(1).restart();
                    }
                    
                    function toggleScope() {
                        fileScopeMode = !fileScopeMode;
                        bridge.postMessage({ type: 'toggleScope', fileScope: fileScopeMode });
                    }
                    
                    initGraph();
                </script>
            </body>
            </html>
        """.trimIndent()

        browser.loadHTML(html)
    }

    private fun injectBridge() {
        val js = "window.__intellijBridge = function(msg) { ${jsQuery.inject("msg")} };"
        browser.cefBrowser.executeJavaScript(js, "", 0)
    }

    private fun loadGraphData() {
        val basePath = project.basePath ?: return
        val settings = PluginSettings.getInstance()
        val subPath = settings.graphRootPath

        val graphData: GraphData = if (fileScope) {
            val currentFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
            if (currentFile != null) {
                GraphBuilder.buildFileGraph(currentFile.path, basePath)
            } else {
                GraphBuilder.buildWorkspaceGraph(basePath, subPath)
            }
        } else {
            GraphBuilder.buildWorkspaceGraph(basePath, subPath)
        }

        // Convert to JSON and send to browser
        val nodesJson = graphData.nodes.joinToString(",") { node ->
            """{"id":"${escapeJs(node.id)}","label":"${escapeJs(node.label)}","kind":"${node.kind.name.lowercase()}","filePath":${node.filePath?.let { "\"${escapeJs(it)}\"" } ?: "null"},"relativePath":${node.relativePath?.let { "\"${escapeJs(it)}\"" } ?: "null"},"paramCount":${node.paramCount},"requiredCount":${node.requiredCount},"isScope":${node.isScope}}"""
        }
        val edgesJson = graphData.edges.joinToString(",") { edge ->
            """{"source":"${escapeJs(edge.source)}","target":"${escapeJs(edge.target)}","label":${edge.label?.let { "\"${escapeJs(it)}\"" } ?: "null"},"direction":${edge.direction?.let { "\"${escapeJs(it)}\"" } ?: "null"}}"""
        }
        val json = """{"nodes":[$nodesJson],"edges":[$edgesJson]}"""

        browser.cefBrowser.executeJavaScript("updateGraph($json);", "", 0)
    }

    private fun handleMessage(jsonMsg: String) {
        try {
            val json = Json.parseToJsonElement(jsonMsg).jsonObject
            val type = json["type"]?.jsonPrimitive?.content ?: return

            when (type) {
                "openFile" -> {
                    val filePath = json["filePath"]?.jsonPrimitive?.content ?: return
                    // Must run on EDT since we're called from a CEF callback thread
                    ApplicationManager.getApplication().invokeLater {
                        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return@invokeLater
                        val descriptor = OpenFileDescriptor(project, virtualFile, 0, 0)
                        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
                    }
                }
                "toggleScope" -> {
                    fileScope = json["fileScope"]?.jsonPrimitive?.boolean ?: false
                    loadGraphData()
                }
            }
        } catch (e: Exception) {
            // Ignore parse errors
        }
    }

    fun refresh() {
        loadGraphData()
    }

    private fun loadResource(path: String): String {
        return this::class.java.getResourceAsStream(path)?.bufferedReader()?.readText() ?: ""
    }

    private fun escapeJs(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
    }
}
