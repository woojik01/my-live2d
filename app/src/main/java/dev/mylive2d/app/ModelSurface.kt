package dev.mylive2d.app

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.view.MotionEvent
import dev.mylive2d.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

/** GL thread only owns textures/programs; UI sends immutable scene snapshots. */
class ModelSurface(context: Context, private val vm: EditorViewModel): GLSurfaceView(context) {
    private val painter=MeshRenderer(vm)
    private var last=Vec2(); private var lastSpan=0f; private var pointers=0
    private var vertex=-1; private var movingPart=false
    private var control=-1; private var rotating=false; private var topologyTap=false
    init { setEGLContextClientVersion(2); setRenderer(painter); renderMode=RENDERMODE_WHEN_DIRTY; preserveEGLContextOnPause=true }
    fun update() {
        painter.scene=Scene(vm.renderProject,vm.camera,vm.selectedId,vm.meshMode,vm.grid,vm.background,null,vm.selectedDeformerId,vm.selectedVertex)
        requestRender()
    }
    private fun center(e: MotionEvent): Vec2 = if(e.pointerCount>=2) Vec2((e.getX(0)+e.getX(1))/2,(e.getY(0)+e.getY(1))/2) else Vec2(e.x,e.y)
    private fun span(e: MotionEvent) = if(e.pointerCount>=2) hypot(e.getX(0)-e.getX(1),e.getY(0)-e.getY(1)) else 0f
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if(vm.busy) return true
        val w=width.toFloat(); val h=height.toFloat(); val c=center(e)
        fun nearest(points: List<Vec2>, radius: Float=28f): Int {
            val i=points.indices.minByOrNull { n -> val s=vm.camera.screen(points[n],w,h); hypot(s.x-c.x,s.y-c.y) } ?: return -1
            val screen=vm.camera.screen(points[i],w,h)
            return if(hypot(screen.x-c.x,screen.y-c.y)<=radius*resources.displayMetrics.density) i else -1
        }
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true); last=c; pointers=1
                vertex=-1; movingPart=false; control=-1; rotating=false; topologyTap=false
                val world=vm.camera.world(c,w,h); val eval=RigEvaluator(vm.renderProject)
                val selected=vm.selected; val node=vm.selectedDeformer
                if(node!=null && eval.visible(node) && !eval.locked(node)) {
                    if(node.kind==DeformerKind.WARP && vm.meshMode) control=nearest(eval.controlWorld(node))
                    if(control<0) {
                        val handle=nearest(listOf(eval.pivotWorld(node),eval.axisWorld(node)),32f)
                        movingPart=handle==0; rotating=handle==1
                    }
                } else if(vm.meshMode && selected!=null && eval.visible(selected) && !eval.locked(selected)) {
                    val points=eval.partWorld(selected)
                    vertex=nearest(points); vm.selectedVertex=vertex
                    if(vm.meshTool==1 && !vm.keyEditing) { vm.addMeshVertex(world); topologyTap=true; vertex=-1 }
                    if(vm.meshTool==2 && !vm.keyEditing) { if(vertex>=0) vm.removeMeshVertex(vertex); topologyTap=true; vertex=-1 }
                } else if(!vm.keyEditing) {
                    val hit=vm.project.parts.asReversed().firstOrNull { part -> eval.visible(part) && MeshTopology.hit(part.mesh,eval.partWorld(part),world)!=null }
                    if(hit!=null) { if(hit.id!=vm.selectedId) vm.select(hit.id); movingPart=!eval.locked(hit) }
                }
                vm.beginGesture()
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                vm.endGesture(); vertex=-1; movingPart=false; control=-1; rotating=false; topologyTap=false
                pointers=0; last=c; lastSpan=span(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if(e.pointerCount>=2) {
                    val currentSpan=span(e)
                    if(pointers>=2 && lastSpan>0) vm.camera=vm.camera.gesture(last,c-last,currentSpan/lastSpan,w,h)
                    lastSpan=currentSpan
                } else if(pointers==1 && !topologyTap) {
                    val delta=(c-last)*(1/vm.camera.zoom)
                    when {
                        control>=0 -> vm.moveWarpControl(control,delta)
                        rotating -> vm.rotateDeformer(vm.camera.world(last,w,h),vm.camera.world(c,w,h))
                        vertex>=0 -> vm.moveVertexWorld(vertex,delta)
                        movingPart -> vm.moveElementWorld(delta)
                        else -> vm.camera=vm.camera.copy(center=vm.camera.center-delta)
                    }
                }
                pointers=e.pointerCount; last=c; update()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vm.endGesture(); vertex=-1; control=-1; rotating=false; movingPart=false; topologyTap=false; pointers=0; performClick()
            }
        }
        update(); return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}

private data class Scene(val project: Project, val camera: Camera, val selected: String?, val mesh: Boolean, val grid: Boolean, val background: Int, val draft: List<Vec2>?, val deformer: String?=null, val vertex: Int=-1)
private class MeshRenderer(private val vm: EditorViewModel): GLSurfaceView.Renderer {
    @Volatile var scene=Scene(Project(),Camera(),null,false,true,0,null)
    private var width=1; private var height=1; private var program=0; private var backdrop=0
    private val textures=mutableMapOf<String,Int>(); private val failed=mutableSetOf<String>()
    override fun onSurfaceCreated(gl: GL10?,config: EGLConfig?) {
        textures.clear(); failed.clear()
        program=program("""attribute vec2 position; attribute vec2 uv; varying vec2 tex;
            void main(){ gl_Position=vec4(position,0.,1.); tex=uv; gl_PointSize=12.; }""",
            """precision mediump float; uniform sampler2D image; uniform float opacity; uniform int mode; varying vec2 tex;
            void main(){ if(mode==1) gl_FragColor=vec4(.5,.7,1.,1.); else if(mode==2) gl_FragColor=vec4(1.,.65,.2,1.); else gl_FragColor=texture2D(image,tex)*opacity; }""")
        backdrop=program("attribute vec2 position; void main(){gl_Position=vec4(position,0.,1.);}",
            """precision mediump float; uniform int background; uniform int grid; uniform vec2 viewport; uniform vec2 camera; uniform float zoom;
            void main(){
              vec2 pixel=gl_FragCoord.xy;
              float checker=mod(floor(pixel.x/20.)+floor(pixel.y/20.),2.);
              vec3 color=mix(vec3(.20,.22,.27),vec3(.25,.27,.32),checker);
              if(background==1) color=vec3(.08,.09,.12); if(background==2) color=vec3(.94);
              vec2 world=(vec2(pixel.x,viewport.y-pixel.y)-viewport/2.)/zoom+camera;
              vec2 line=abs(mod(world+50.,100.)-50.);
              if(grid==1 && (line.x<.6/zoom || line.y<.6/zoom)) color=mix(color,vec3(.5,.6,.8),.25);
              gl_FragColor=vec4(color,1.);
            }""")
        glEnable(GL_BLEND); glBlendFunc(GL_ONE,GL_ONE_MINUS_SRC_ALPHA)
    }
    override fun onSurfaceChanged(gl: GL10?,w: Int,h: Int) { width=w.coerceAtLeast(1); height=h.coerceAtLeast(1); glViewport(0,0,width,height) }
    override fun onDrawFrame(gl: GL10?) {
        val s=scene; val eval=RigEvaluator(s.project)
        val used=s.project.assets.map { it.id }.toSet()
        textures.keys.filter { it !in used }.toList().forEach { textures.remove(it)?.let { id -> glDeleteTextures(1,intArrayOf(id),0) } }
        glClearColor(.1f,.1f,.15f,1f); glClear(GL_COLOR_BUFFER_BIT)
        glUseProgram(backdrop)
        val loc=glGetAttribLocation(backdrop,"position"); glEnableVertexAttribArray(loc)
        glVertexAttribPointer(loc,2,GL_FLOAT,false,0,floats(listOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f)))
        glUniform1i(glGetUniformLocation(backdrop,"background"),s.background)
        glUniform1i(glGetUniformLocation(backdrop,"grid"),if(s.grid) 1 else 0)
        glUniform2f(glGetUniformLocation(backdrop,"viewport"),width.toFloat(),height.toFloat())
        glUniform2f(glGetUniformLocation(backdrop,"camera"),s.camera.center.x,s.camera.center.y)
        glUniform1f(glGetUniformLocation(backdrop,"zoom"),s.camera.zoom)
        glDrawArrays(GL_TRIANGLE_STRIP,0,4); glDisableVertexAttribArray(loc)
        glUseProgram(program)
        val position=glGetAttribLocation(program,"position"); val uv=glGetAttribLocation(program,"uv")
        glEnableVertexAttribArray(position); glEnableVertexAttribArray(uv)
        s.project.parts.filter { eval.visible(it) }.forEach { part ->
            val texture=texture(part.assetId) ?: return@forEach
            val points=if(part.id==s.selected && s.draft!=null) s.draft else ParameterEngine.vertices(s.project,part)
            val clip=eval.partWorld(part,points).flatMap { p -> val screen=s.camera.screen(p,width.toFloat(),height.toFloat()); listOf(screen.x/width*2-1,1-screen.y/height*2) }
            glVertexAttribPointer(position,2,GL_FLOAT,false,0,floats(clip))
            glVertexAttribPointer(uv,2,GL_FLOAT,false,0,floats(part.mesh.uv.flatMap { listOf(it.x,it.y) }))
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D,texture)
            glUniform1i(glGetUniformLocation(program,"image"),0)
            glUniform1f(glGetUniformLocation(program,"opacity"),part.opacity)
            glUniform1i(glGetUniformLocation(program,"mode"),0)
            val t=part.mesh.triangles; glDrawElements(GL_TRIANGLES,t.size,GL_UNSIGNED_SHORT,shorts(t))
            if(part.id==s.selected) {
                glUniform1i(glGetUniformLocation(program,"mode"),1)
                val lines=if(s.mesh) t.chunked(3).flatMap { listOf(it[0],it[1],it[1],it[2],it[2],it[0]) } else MeshTopology.boundaryEdges(part.mesh)
                glDrawElements(GL_LINES,lines.size,GL_UNSIGNED_SHORT,shorts(lines))
                if(s.mesh) {
                    glDrawArrays(GL_POINTS,0,points.size)
                    if(s.vertex in points.indices) { glUniform1i(glGetUniformLocation(program,"mode"),2); glDrawElements(GL_POINTS,1,GL_UNSIGNED_SHORT,shorts(listOf(s.vertex))) }
                }
            }
        }
        glDisableVertexAttribArray(uv); glVertexAttrib2f(uv,0f,0f)
        s.deformer?.let { id -> s.project.deformers.firstOrNull { it.id==id && eval.visible(it) }?.let { node ->
            fun positions(points: List<Vec2>)=floats(points.flatMap { p -> val screen=s.camera.screen(p,width.toFloat(),height.toFloat()); listOf(screen.x/width*2-1,1-screen.y/height*2) })
            glUniform1i(glGetUniformLocation(program,"mode"),2)
            if(node.kind==DeformerKind.WARP) {
                val controls=eval.controlWorld(node)
                glVertexAttribPointer(position,2,GL_FLOAT,false,0,positions(controls))
                val lines=mutableListOf<Int>()
                for(y in 0..node.rows) for(x in 0..node.columns) {
                    val i=y*(node.columns+1)+x
                    if(x<node.columns) lines.addAll(listOf(i,i+1))
                    if(y<node.rows) lines.addAll(listOf(i,i+node.columns+1))
                }
                glDrawElements(GL_LINES,lines.size,GL_UNSIGNED_SHORT,shorts(lines))
                if(s.mesh) glDrawArrays(GL_POINTS,0,controls.size)
            }
            val pivot=eval.pivotWorld(node); val axis=eval.axisWorld(node)
            glVertexAttribPointer(position,2,GL_FLOAT,false,0,positions(listOf(pivot,axis)))
            glDrawArrays(GL_LINES,0,2); glDrawArrays(GL_POINTS,0,2)
        } }
        glDisableVertexAttribArray(position)
    }
    private fun texture(id: String): Int? {
        textures[id]?.let { return it }; if(id in failed) return null
        return try {
            val bitmap=BitmapFactory.decodeFile(vm.repository.assetFile(id).absolutePath) ?: error("Cannot decode texture")
            val maxSize=IntArray(1); glGetIntegerv(GL_MAX_TEXTURE_SIZE,maxSize,0)
            require(bitmap.width<=maxSize[0] && bitmap.height<=maxSize[0])
            val ids=IntArray(1); glGenTextures(1,ids,0); glBindTexture(GL_TEXTURE_2D,ids[0])
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE)
            try { GLUtils.texImage2D(GL_TEXTURE_2D,0,bitmap,0) } finally { bitmap.recycle() }
            textures[id]=ids[0]; ids[0]
        } catch(e: Exception) { failed.add(id); vm.message("Texture: ${e.message}"); null }
    }
    private fun floats(v: List<Float>)=ByteBuffer.allocateDirect(v.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { v.forEach { put(it) }; position(0) }
    private fun shorts(v: List<Int>)=ByteBuffer.allocateDirect(v.size*2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { v.forEach { put(it.toShort()) }; position(0) }
    private fun program(vertex: String,fragment: String): Int {
        fun shader(type: Int,source: String): Int {
            val id=glCreateShader(type); glShaderSource(id,source); glCompileShader(id)
            val ok=IntArray(1); glGetShaderiv(id,GL_COMPILE_STATUS,ok,0); check(ok[0]!=0) { glGetShaderInfoLog(id) }; return id
        }
        val v=shader(GL_VERTEX_SHADER,vertex); val f=shader(GL_FRAGMENT_SHADER,fragment)
        val p=glCreateProgram(); glAttachShader(p,v); glAttachShader(p,f); glLinkProgram(p)
        val ok=IntArray(1); glGetProgramiv(p,GL_LINK_STATUS,ok,0); check(ok[0]!=0) { glGetProgramInfoLog(p) }
        glDeleteShader(v); glDeleteShader(f); return p
    }
}
