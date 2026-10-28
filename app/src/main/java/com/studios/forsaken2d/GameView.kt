package com.studios.forsaken2d

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.hypot

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    private var gameThread: Thread? = null
    @Volatile private var isPlaying = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var state = 0 // 0=Lobby, 1=Game, 2=Over
    
    private var kIndex = 0; private var kSkinIdx = 0
    private var sIndex = 0; private var sSkinIdx = 0

    private lateinit var map: GameMap
    private lateinit var match: MatchManager
    private var pChar: Char? = null; private var kChar: Char? = null
    private var pX = 0f; private var pY = 0f
    private var kX = 0f; private var kY = 0f
    private var pHp = 100f
    
    private var currentCooldowns = mutableListOf<Float>()
    private var speedBuffTimer = 0f
    private var stunTimer = 0f

    private var joyPtr = -1
    private var joyCx = 250f; private var joyCy = 800f
    private var joyKx = 250f; private var joyKy = 800f
    private val joyR = 120f

    init { holder.addCallback(this) }

    override fun surfaceCreated(holder: SurfaceHolder) { isPlaying = true; gameThread = Thread(this).also { it.start() } }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { isPlaying = false; SoundManager.stopMusic() }

    override fun run() {
        var last = System.nanoTime()
        while (isPlaying) {
            val now = System.nanoTime()
            val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
            last = now
            if (state == 1) updateGame(dt)

            val c = holder.lockCanvas() ?: continue
            when (state) {
                0 -> drawLobby(c)
                1 -> drawGame(c)
                2 -> drawGameOver(c)
            }
            holder.unlockCanvasAndPost(c)
            try { Thread.sleep(16) } catch (_: Exception) {}
        }
    }

    private fun drawLobby(c: Canvas) {
        c.drawColor(Color.BLACK)
        val w = width.toFloat(); val h = height.toFloat()
        paint.color = Color.parseColor("#D32F2F"); paint.textSize = 70f; paint.textAlign = Paint.Align.CENTER
        c.drawText("FORSAKEN V6.2", w / 2f, 100f, paint)

        val k = DB.killers[kIndex]; val kS = k.skins[kSkinIdx % k.skins.size]
        val s = DB.survivors[sIndex]; val sS = s.skins[sSkinIdx % s.skins.size]

        paint.textSize = 40f; paint.color = Color.RED
        c.drawText("KILLER: ${k.name}", w / 2f, 220f, paint)
        paint.textSize = 60f; paint.color = Color.GRAY
        c.drawText("<", w / 4f, 230f, paint); c.drawText(">", w * 3/4f, 230f, paint)
        paint.textSize = 30f; paint.color = kS.color
        c.drawText("[ ${kS.name} ]", w / 2f, 280f, paint)

        paint.textSize = 40f; paint.color = Color.GREEN
        c.drawText("SURVIVOR: ${s.name}", w / 2f, 400f, paint)
        paint.textSize = 60f; paint.color = Color.GRAY
        c.drawText("<", w / 4f, 410f, paint); c.drawText(">", w * 3/4f, 410f, paint)
        paint.textSize = 30f; paint.color = sS.color
        c.drawText("[ ${sS.name} ]", w / 2f, 460f, paint)

        paint.textSize = 30f
        paint.color = Color.parseColor("#7CFFB2")
        c.drawRect(w / 2f - 260f, h - 180f, w / 2f - 20f, h - 100f, paint)
        paint.color = Color.BLACK
        c.drawText("CHƠI OFFLINE", w / 2f - 140f, h - 130f, paint)
    }

    private fun startGame() {
        map = DB.getRandomMap()
        match = MatchManager()
        kChar = DB.killers[kIndex]
        pChar = DB.survivors[sIndex]
        pX = map.playerSpawn.first; pY = map.playerSpawn.second
        kX = map.killerSpawn.first; kY = map.killerSpawn.second
        pHp = pChar!!.hp
        
        currentCooldowns.clear()
        pChar!!.abilities.forEach { currentCooldowns.add(0f) }
        speedBuffTimer = 0f; stunTimer = 0f

        // Khởi tạo trận đấu (1 người -> Bật LMS luôn)
        val sSkinName = pChar!!.skins[sSkinIdx % pChar!!.skins.size].name
        match.start(1, kChar!!.id, pChar!!.id, sSkinName, context)

        state = 1
    }

    private fun updateGame(dt: Float) {
        val pC = pChar ?: return
        val kC = kChar ?: return
        if (match.isOver) { state = 2; return }

        for (i in currentCooldowns.indices) if (currentCooldowns[i] > 0) currentCooldowns[i] -= dt
        if (speedBuffTimer > 0) speedBuffTimer -= dt
        if (stunTimer > 0) stunTimer -= dt

        var moveX = 0f; var moveY = 0f
        if (joyPtr != -1) {
            val dx = joyKx - joyCx; val dy = joyKy - joyCy
            val d = hypot(dx, dy)
            if (d > 10f) { moveX = dx / joyR; moveY = dy / joyR }
        }

        val spd = if (speedBuffTimer > 0) pC.speed * 1.5f else pC.speed
        val nextX = pX + moveX * spd * dt
        val nextY = pY + moveY * spd * dt

        // Va chạm tường
        var collideX = false; var collideY = false
        for (obs in map.obstacles) {
            val rect = RectF(obs.x, obs.y, obs.x + obs.w, obs.y + obs.h)
            if (rect.intersects(nextX - pC.radius, pY - pC.radius, nextX + pC.radius, pY + pC.radius)) collideX = true
            if (rect.intersects(pX - pC.radius, nextY - pC.radius, pX + pC.radius, nextY + pC.radius)) collideY = true
        }
        if (!collideX) pX = nextX.coerceIn(pC.radius, map.width - pC.radius)
        if (!collideY) pY = nextY.coerceIn(pC.radius, map.height - pC.radius)

        // Sửa máy phát
        for (gen in map.generators) {
            if (!gen.isCompleted && hypot(pX - gen.x, pY - gen.y) < 100f) {
                gen.progress += 20f * dt 
                if (gen.progress >= 100f) {
                    gen.progress = 100f; gen.isCompleted = true
                    match.onGeneratorDone() 
                }
            }
        }

        // Killer AI
        if (stunTimer <= 0) {
            val kDx = pX - kX; val kDy = pY - kY
            val kDist = hypot(kDx, kDy)
            if (kDist > 0) { kX += (kDx / kDist) * kC.speed * dt; kY += (kDy / kDist) * kC.speed * dt }
        }

        // Sát thương
        if (hypot(pX - kX, pY - kY) < kC.radius + pC.radius && stunTimer <= 0) {
            pHp -= 20f * dt
            if (pHp <= 0) { pHp = 0f; state = 2 }
        }

        // Update Timer
        val sSkinName = pC.skins[sSkinIdx % pC.skins.size].name
        match.update(dt, 1, kC.id, pC.id, sSkinName, context)
    }

    private fun drawGame(c: Canvas) {
        val pC = pChar ?: return
        val kC = kChar ?: return
        val pSkin = pC.skins[sSkinIdx % pC.skins.size]
        val kSkin = kC.skins[kSkinIdx % kC.skins.size]

        val camX = (pX - width / 2f).coerceIn(0f, (map.width - width).coerceAtLeast(0f))
        val camY = (pY - height / 2f).coerceIn(0f, (map.height - height).coerceAtLeast(0f))

        c.drawColor(map.bgColor)
        c.save()
        c.translate(-camX, -camY)

        paint.color = Color.parseColor("#333333")
        for (obs in map.obstacles) c.drawRect(obs.x, obs.y, obs.x + obs.w, obs.y + obs.h, paint)

        for (gen in map.generators) {
            paint.color = if (gen.isCompleted) Color.GREEN else Color.YELLOW
            c.drawCircle(gen.x, gen.y, gen.radius, paint)
            paint.style = Paint.Style.STROKE; paint.color = Color.WHITE; paint.strokeWidth = 3f
            c.drawCircle(gen.x, gen.y, gen.radius, paint); paint.style = Paint.Style.FILL

            if (!gen.isCompleted && gen.progress > 0) {
                paint.style = Paint.Style.STROKE; paint.color = Color.CYAN; paint.strokeWidth = 6f
                val rect = RectF(gen.x - gen.radius - 10f, gen.y - gen.radius - 10f, gen.x + gen.radius + 10f, gen.y + gen.radius + 10f)
                c.drawArc(rect, -90f, 360f * (gen.progress / 100f), false, paint)
                paint.style = Paint.Style.FILL
            }
        }

        paint.color = if (stunTimer > 0) Color.GRAY else kSkin.color
        c.drawCircle(kX, kY, kC.radius, paint)
        paint.color = pSkin.color
        c.drawCircle(pX, pY, pC.radius, paint)

        c.restore()

        // HUD
        paint.color = if (match.isLMS) Color.MAGENTA else Color.WHITE
        paint.textSize = 70f; paint.textAlign = Paint.Align.CENTER
        c.drawText(match.formatTime(), width / 2f, 100f, paint)
        
        if (match.isLMS) {
            paint.textSize = 30f; c.drawText("LMS: ${match.lmsTheme}", width / 2f, 140f, paint)
        }

        val doneGens = map.generators.count { it.isCompleted }
        paint.textSize = 30f; paint.color = Color.MAGENTA
        c.drawText("Malice: ${match.malice} | Gens: $doneGens/5", width / 2f, 180f, paint)

        paint.color = Color.RED; paint.textAlign = Paint.Align.LEFT
        c.drawRect(50f, 50f, 50f + (pHp * 3), 80f, paint)

        paint.style = Paint.Style.STROKE; paint.color = Color.argb(100, 255, 255, 255); paint.strokeWidth = 5f
        c.drawCircle(joyCx, joyCy, joyR, paint)
        paint.style = Paint.Style.FILL; paint.color = Color.argb(200, 255, 255, 255)
        c.drawCircle(joyKx, joyKy, 50f, paint)

        val btnStartX = width - 120f; val btnY = height - 120f
        for (i in pC.abilities.indices) {
            val ab = pC.abilities[i]; val cx = btnStartX - (i * 160f); val cy = btnY; val r = 60f
            paint.color = Color.argb(150, 30, 30, 30); c.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.STROKE; paint.color = if (currentCooldowns[i] <= 0) Color.CYAN else Color.DKGRAY
            paint.strokeWidth = 6f; c.drawCircle(cx, cy, r, paint); paint.style = Paint.Style.FILL
            
            if (currentCooldowns[i] > 0) {
                val sweep = 360f * (currentCooldowns[i] / ab.cooldown)
                paint.color = Color.argb(200, 0, 0, 0)
                c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, sweep, true, paint)
                paint.color = Color.WHITE; paint.textSize = 30f; paint.textAlign = Paint.Align.CENTER
                c.drawText(String.format("%.1f", currentCooldowns[i]), cx, cy + 10f, paint)
            } else {
                paint.color = Color.WHITE; paint.textSize = 24f; paint.textAlign = Paint.Align.CENTER
                c.drawText(ab.name, cx, cy + 10f, paint)
            }
        }
    }

    private fun drawGameOver(c: Canvas) {
        c.drawColor(Color.BLACK)
        
        if (pHp <= 0) {
            paint.color = Color.RED; paint.textSize = 100f; paint.textAlign = Paint.Align.CENTER
            c.drawText("BẠN ĐÃ CHẾT", width / 2f, height / 2f, paint)
        } else {
            paint.color = Color.GREEN; paint.textSize = 80f; paint.textAlign = Paint.Align.CENTER
            c.drawText("SURVIVOR WIN", width / 2f, height / 2f - 50f, paint)
            paint.color = Color.WHITE; paint.textSize = 40f
            c.drawText("Killer đã thua vì hết giờ!", width / 2f, height / 2f + 50f, paint)
        }
        
        paint.color = Color.WHITE; paint.textSize = 40f
        c.drawText("Bấm để về Lobby", width / 2f, height / 2f + 150f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x; val y = event.y
        val w = width.toFloat(); val h = height.toFloat()

        if (state == 2) {
            if (event.action == MotionEvent.ACTION_DOWN) { state = 0; SoundManager.stopMusic() }
            return true
        }

        if (state == 0) {
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (y in 180f..260f) {
                    if (x < w / 3f) kIndex = (kIndex - 1 + DB.killers.size) % DB.killers.size
                    if (x > w * 2/3f) kIndex = (kIndex + 1) % DB.killers.size
                    kSkinIdx = 0
                } else if (y in 260f..330f) {
                    val max = DB.killers[kIndex].skins.size
                    if (x < w / 3f) kSkinIdx = (kSkinIdx - 1 + max) % max
                    if (x > w * 2/3f) kSkinIdx = (kSkinIdx + 1) % max
                } else if (y in 360f..440f) {
                    if (x < w / 3f) sIndex = (sIndex - 1 + DB.survivors.size) % DB.survivors.size
                    if (x > w * 2/3f) sIndex = (sIndex + 1) % DB.survivors.size
                    sSkinIdx = 0
                } else if (y in 440f..510f) {
                    val max = DB.survivors[sIndex].skins.size
                    if (x < w / 3f) sSkinIdx = (sSkinIdx - 1 + max) % max
                    if (x > w * 2/3f) sSkinIdx = (sSkinIdx + 1) % max
                } else if (y in h - 180f..h - 100f && x in w / 2f - 260f..w / 2f - 20f) {
                    startGame()
                }
            }
            return true
        }

        if (state == 1) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val ptr = event.actionIndex
                    val px = event.getX(ptr); val py = event.getY(ptr)
                    val pC = pChar ?: return true
                    
                    var clickedSkill = false
                    val btnStartX = w - 120f; val btnY = h - 120f
                    
                    for (i in pC.abilities.indices) {
                        val cx = btnStartX - (i * 160f)
                        if (hypot(px - cx, py - btnY) < 70f) {
                            if (currentCooldowns[i] <= 0) {
                                currentCooldowns[i] = pC.abilities[i].cooldown
                                applySkillEffect(pC.abilities[i])
                            }
                            clickedSkill = true; break
                        }
                    }

                    if (!clickedSkill && px < w / 2f) {
                        joyPtr = event.getPointerId(ptr)
                        joyCx = px; joyCy = py; joyKx = px; joyKy = py
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until event.pointerCount) {
                        if (event.getPointerId(i) == joyPtr) {
                            val dx = event.getX(i) - joyCx; val dy = event.getY(i) - joyCy
                            val d = hypot(dx, dy)
                            if (d > joyR) {
                                joyKx = joyCx + (dx / d) * joyR; joyKy = joyCy + (dy / d) * joyR
                            } else {
                                joyKx = event.getX(i); joyKy = event.getY(i)
                            }
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.actionIndex) == joyPtr) {
                        joyPtr = -1; joyKx = joyCx; joyKy = joyCy
                    }
                }
            }
        }
        return true
    }

    private fun applySkillEffect(ability: Ability) {
        when (ability.type) {
            1 -> if (hypot(pX - kX, pY - kY) < 250f) stunTimer = 3f 
            2 -> speedBuffTimer = 5f 
            3 -> pHp = (pHp + 50f).coerceAtMost(pChar!!.hp) 
        }
    }
}
