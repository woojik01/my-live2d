package dev.mylive2d.core

import kotlin.math.abs

object MeshTopology {
    data class Hit(val triangle: Int, val weights: List<Float>)
    private fun cross(a: Vec2,b: Vec2,c: Vec2)=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)
    fun hit(mesh: Mesh, points: List<Vec2>, point: Vec2): Hit? {
        mesh.triangles.chunked(3).forEachIndexed { i,t ->
            val a=points[t[0]]; val b=points[t[1]]; val c=points[t[2]]; val det=cross(a,b,c)
            if(abs(det)>.00001f) {
                val w0=cross(point,b,c)/det; val w1=cross(point,c,a)/det; val w2=1-w0-w1
                if(minOf(w0,w1,w2)>=-.00001f) return Hit(i,listOf(w0,w1,w2))
            }
        }
        return null
    }
    fun boundaryEdges(mesh: Mesh): List<Int> {
        val edges=linkedMapOf<Pair<Int,Int>,Int>()
        mesh.triangles.chunked(3).forEach { t -> for(i in 0..2) {
            val a=t[i]; val b=t[(i+1)%3]; val key=minOf(a,b) to maxOf(a,b)
            edges[key]=(edges[key] ?: 0)+1
        } }
        return edges.filterValues { it==1 }.keys.flatMap { listOf(it.first,it.second) }
    }
    fun insert(project: Project, partId: String, hit: Hit): Project {
        val part=project.parts.first { it.id==partId }; val mesh=part.mesh
        require(mesh.vertices.size<1024 && mesh.triangles.size+6<=6144) { "Mesh limit reached" }
        require(hit.triangle in 0 until mesh.triangles.size/3 && hit.weights.size==3)
        require(hit.weights.all { it.isFinite() && it>.0001f } && abs(hit.weights.sum()-1)<.0001f) { "Add a vertex inside a triangle, away from its edges" }
        val triangle=mesh.triangles.subList(hit.triangle*3,hit.triangle*3+3)
        fun weighted(points: List<Vec2>)=triangle.indices.fold(Vec2()) { sum,i -> sum+points[triangle[i]]*hit.weights[i] }
        val vertex=mesh.vertices.size
        val triangles=mesh.triangles.take(hit.triangle*3)+listOf(triangle[0],triangle[1],vertex,triangle[1],triangle[2],vertex,triangle[2],triangle[0],vertex)+mesh.triangles.drop(hit.triangle*3+3)
        val next=mesh.copy(vertices=mesh.vertices+weighted(mesh.vertices),uv=mesh.uv+weighted(mesh.uv),triangles=triangles)
        val result=project.copy(parts=project.parts.map { if(it.id==partId) it.copy(mesh=next) else it },
            parameters=project.parameters.map { p -> p.copy(bindings=p.bindings.map { b -> if(b.partId!=partId) b else b.copy(keys=b.keys.map { it.copy(vertices=it.vertices+weighted(it.vertices)) }) }) })
        Validation.check(result); return result
    }
    /** Interior removal fills its star-shaped hole; boundary vertices stay protected to preserve texture coverage. */
    fun remove(project: Project,partId: String,index: Int): Project {
        val part=project.parts.first { it.id==partId }; val mesh=part.mesh
        require(index in mesh.vertices.indices && mesh.vertices.size>3)
        require(index !in boundaryEdges(mesh)) { "Boundary vertices are protected; select an interior vertex" }
        val triangles=mesh.triangles.chunked(3); val incident=triangles.filter { index in it }
        require(incident.size>=3) { "Vertex has no removable interior fan" }
        val edges=incident.map { t -> t.filter { it!=index } }
        val adjacency=mutableMapOf<Int,MutableList<Int>>()
        edges.forEach { e -> require(e.size==2); adjacency.getOrPut(e[0]) { mutableListOf() }.add(e[1]); adjacency.getOrPut(e[1]) { mutableListOf() }.add(e[0]) }
        require(adjacency.values.all { it.size==2 }) { "Non-manifold vertex cannot be removed" }
        val first=adjacency.keys.min(); val ring=mutableListOf(first); var previous=-1; var current=first
        while(true) {
            val next=adjacency.getValue(current).first { it!=previous }
            if(next==first) break
            require(next !in ring && ring.size<adjacency.size) { "Invalid mesh fan" }
            ring.add(next); previous=current; current=next
        }
        require(ring.size==adjacency.size)
        val filled=triangulate(ring,mesh.vertices)
        val kept=(triangles.filter { index !in it }.flatten()+filled).map { if(it>index) it-1 else it }
        val next=mesh.copy(vertices=mesh.vertices.filterIndexed { i,_ -> i!=index },uv=mesh.uv.filterIndexed { i,_ -> i!=index },triangles=kept)
        val result=project.copy(parts=project.parts.map { if(it.id==partId) it.copy(mesh=next) else it },parameters=project.parameters.map { p -> p.copy(bindings=p.bindings.map { b -> if(b.partId!=partId) b else b.copy(keys=b.keys.map { k -> k.copy(vertices=k.vertices.filterIndexed { i,_ -> i!=index }) }) }) })
        Validation.check(result); return result
    }
    private fun triangulate(input: List<Int>,points: List<Vec2>): List<Int> {
        val signed=input.indices.sumOf { i -> val a=points[input[i]]; val b=points[input[(i+1)%input.size]]; (a.x*b.y-a.y*b.x).toDouble() }
        require(abs(signed)>.0001) { "Degenerate mesh fan" }
        val ring=(if(signed>0) input else input.reversed()).toMutableList(); val result=mutableListOf<Int>()
        while(ring.size>3) {
            var clipped=false
            for(i in ring.indices) {
                val a=ring[(i+ring.size-1)%ring.size]; val b=ring[i]; val c=ring[(i+1)%ring.size]
                if(cross(points[a],points[b],points[c])<=.00001f) continue
                val contains=ring.any { v -> v!=a && v!=b && v!=c && cross(points[a],points[b],points[v])>=0 && cross(points[b],points[c],points[v])>=0 && cross(points[c],points[a],points[v])>=0 }
                if(!contains) { result.addAll(listOf(a,b,c)); ring.removeAt(i); clipped=true; break }
            }
            require(clipped) { "Folded or intersecting mesh fan cannot be removed" }
        }
        result.addAll(ring); return result
    }
}
