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
    init { setEGLContextClientVersion(2); setRenderer(painter); renderMode=RENDERMODE_WHEN_DIRTY; preserveEGLContextOnPause=true }
    fun update() {
        painter.scene=Scene(vm.project,vm.camera,vm.selectedId,vm.meshMode,vm.grid,vm.background,if(vm.keyEditing) vm.draft else null)
        requestRender()
    }
    private fun center(e: MotionEvent): Vec2 = if(e.pointerCount>=2) Vec2((e.getX(0)+e.getX(1))/2,(e.getY(0)+e.getY(1))/2) else Vec2(e.x,e.y)
    private fun span(e: MotionEvent) = if(e.pointerCount>=2) hypot(e.getX(0)-e.getX(1),e.getY(0)-e.getY(1)) else 0f
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if(vm.busy) return true
        val w=width.toFloat(); val h=height.toFloat(); val c=center(e)
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true); last=c; pointers=1; vertex=-1; movingPart=false
                val world=vm.camera.world(c,w,h); val selected=vm.selected
                if(vm.meshMode && selected != null && selected.visible && !selected.locked) {
                    val points=vm.draft ?: ParameterEngine.vertices(vm.project,selected)
                    vertex=points.indices.minByOrNull { i -> val s=vm.camera.screen(selected.transform.apply(points[i]),w,h); hypot(s.x-c.x,s.y-c.y) } ?: -1
                    if(vertex>=0) { val s=vm.camera.screen(selected.transform.apply(points[vertex]),w,h); if(hypot(s.x-c.x,s.y-c.y)>28*resources.displayMetrics.density) vertex=-1 }
                } else {
                    val hit=vm.project.parts.asReversed().firstOrNull { part -> part.visible && hitPart(part,world) }
                    if(hit!=null) { if(hit.id!=vm.selectedId) vm.select(hit.id); movingPart=!hit.locked }
                }
                vm.beginGesture()
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> { vm.endGesture(); vertex=-1; movingPart=false; pointers=0; last=c; lastSpan=span(e) }
            MotionEvent.ACTION_MOVE -> {
                if(e.pointerCount>=2) {
                    val currentSpan=span(e)
                    if(pointers>=2 && lastSpan>0) vm.camera=vm.camera.gesture(last,c-last,currentSpan/lastSpan,w,h)
                    lastSpan=currentSpan
                } else if(pointers==1) {
                    val d=(c-last)*(1/vm.camera.zoom)
                    if(vertex>=0) {
                        val transform=vm.selected?.transform ?: Transform()
                        vm.moveVertex(vertex,transform.inverse(transform.position+d)-transform.inverse(transform.position))
                    } else if(movingPart) vm.movePart(d)
                    else vm.camera=vm.camera.copy(center=vm.camera.center-d)
                }
                pointers=e.pointerCount; last=c; update()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { vm.endGesture(); vertex=-1; movingPart=false; pointers=0; performClick() }
        }
        update(); return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private fun hitPart(part: Part, world: Vec2): Boolean {
        val local=part.transform.inverse(world); val v=ParameterEngine.vertices(vm.project,part)
        return part.mesh.triangles.chunked(3).any { t ->
            val a=v[t[0]]; val b=v[t[1]]; val c=v[t[2]]
            fun cross(p: Vec2,q: Vec2,r: Vec2)=(p.x-r.x)*(q.y-r.y)-(q.x-r.x)*(p.y-r.y)
            val d1=cross(local,a,b); val d2=cross(local,b,c); val d3=cross(local,c,a)
            !((d1<0 || d2<0 || d3<0) && (d1>0 || d2>0 || d3>0)) && abs(cross(a,b,c))>.0001f
        }
    }
}
private data class Scene(val project: Project, val camera: Camera, val selected: String?, val mesh: Boolean, val grid: Boolean, val background: Int, val draft: List<Vec2>?)
private class MeshRenderer(private val vm: EditorViewModel): GLSurfaceView.Renderer {
    @Volatile var scene=Scene(Project(),Camera(),null,false,true,0,null)
    private var width=1; private var height=1; private var program=0; private var backdrop=0
    private val textures=mutableMapOf<String,Int>(); private val failed=mutableSetOf<String>()
    override fun onSurfaceCreated(gl: GL10?,config: EGLConfig?) {
        textures.clear(); failed.clear()
        program=program("""attribute vec2 position; attribute vec2 uv; varying vec2 tex;
            void main(){ gl_Position=vec4(position,0.,1.); tex=uv; gl_PointSize=12.; }""",
            """precision mediump float; uniform sampler2D image; uniform float opacity; uniform int mode; varying vec2 tex;
            void main(){ if(mode==1) gl_FragColor=vec4(.5,.7,1.,1.); else gl_FragColor=texture2D(image,tex)*opacity; }""")
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
        val s=scene
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
        s.project.parts.filter { it.visible }.forEach { part ->
            val texture=texture(part.assetId) ?: return@forEach
            val points=if(part.id==s.selected && s.draft!=null) s.draft else ParameterEngine.vertices(s.project,part)
            val clip=points.flatMap { p -> val screen=s.camera.screen(part.transform.apply(p),width.toFloat(),height.toFloat()); listOf(screen.x/width*2-1,1-screen.y/height*2) }
            glVertexAttribPointer(position,2,GL_FLOAT,false,0,floats(clip))
            glVertexAttribPointer(uv,2,GL_FLOAT,false,0,floats(part.mesh.uv.flatMap { listOf(it.x,it.y) }))
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D,texture)
            glUniform1i(glGetUniformLocation(program,"image"),0)
            glUniform1f(glGetUniformLocation(program,"opacity"),part.opacity)
            glUniform1i(glGetUniformLocation(program,"mode"),0)
            val t=part.mesh.triangles; glDrawElements(GL_TRIANGLES,t.size,GL_UNSIGNED_SHORT,shorts(t))
            if(part.id==s.selected) {
                glUniform1i(glGetUniformLocation(program,"mode"),1)
                val lines=if(s.mesh) t.chunked(3).flatMap { listOf(it[0],it[1],it[1],it[2],it[2],it[0]) } else {
                    // Outline texture boundary even after deformation; grid meshes are consistently ordered by UV.
                    val edge=part.mesh.uv.indices.filter { val u=part.mesh.uv[it]; u.x==0f || u.x==1f || u.y==0f || u.y==1f }
                        .sortedBy { val u=part.mesh.uv[it]; when { u.y==0f -> u.x; u.x==1f -> 1+u.y; u.y==1f -> 3-u.x; else -> 4-u.y } }
                    edge.indices.flatMap { listOf(edge[it],edge[(it+1)%edge.size]) }
                }
                glDrawElements(GL_LINES,lines.size,GL_UNSIGNED_SHORT,shorts(lines))
                if(s.mesh) glDrawArrays(GL_POINTS,0,points.size)
            }
        }
        glDisableVertexAttribArray(position); glDisableVertexAttribArray(uv)
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
