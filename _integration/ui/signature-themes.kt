    // ---- Signature set (Steve's November 2026 sheets) ------------------------------------------
    // Twelve scene themes (Aurora Evo, Onyx, Horizon Dusk, Nexus, Luxe, Terrain, Metro, Velocity,
    // Serenity, Nebula, Classic Duo, Crystal) and six map-first frames (Map Focus, Panoramic, Glass
    // Sweep, Triple Dial, Cockpit Edge, Aurora Evolution). Each theme's artwork is painted once into
    // a bitmap at the panel's size, with its middle faded out so the live map shows through, and is
    // then drawn with one call per frame. READY, ECO, the gear, speed-limit and assist symbols are
    // left to the car, which draws its own on top.

    private val signatureLayouts = setOf(ClusterTheme.Layout.SIG_AURORA_EVO, ClusterTheme.Layout.SIG_ONYX,
        ClusterTheme.Layout.SIG_HORIZON, ClusterTheme.Layout.SIG_NEXUS, ClusterTheme.Layout.SIG_LUXE, ClusterTheme.Layout.SIG_TERRAIN,
        ClusterTheme.Layout.SIG_METRO, ClusterTheme.Layout.SIG_VELOCITY, ClusterTheme.Layout.SIG_SERENITY, ClusterTheme.Layout.SIG_NEBULA,
        ClusterTheme.Layout.SIG_CLASSIC, ClusterTheme.Layout.SIG_CRYSTAL, ClusterTheme.Layout.MAP_FOCUS, ClusterTheme.Layout.MAP_PANORAMIC,
        ClusterTheme.Layout.MAP_GLASS, ClusterTheme.Layout.MAP_TRIPLE_DIAL, ClusterTheme.Layout.MAP_COCKPIT, ClusterTheme.Layout.MAP_AURORA)

    private var sigArt: Bitmap? = null
    private var sigArtKey: String? = null

    /** The theme's artwork at this size, painted once. */
    private fun signatureArt(w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val key = "${theme.id}:$w:$h"
        sigArt?.takeIf { key == sigArtKey && !it.isRecycled }?.let { return it }
        sigArt?.recycle()
        val bitmap = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        val c = Canvas(bitmap)
        paintSignatureArt(c, w.toFloat(), h.toFloat())
        // Fade the middle out so the map, its route and the car marker stay in view.
        val keep = theme.layout in setOf(ClusterTheme.Layout.SIG_METRO)
        if (!keep) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }
            val cx = w * .5f; val cy = h * .56f
            p.shader = RadialGradient(cx, cy, h * .52f, intArrayOf(Color.argb(250, 0, 0, 0), Color.argb(190, 0, 0, 0), Color.TRANSPARENT),
                floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP).apply { setLocalMatrix(Matrix().apply { setScale(1.55f, 1f, cx, cy) }) }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        }
        sigArt = bitmap; sigArtKey = key
        return bitmap
    }

    private fun sigNoise(seed: Int, x: Float): Float =
        (sin(x * 1.7f + seed) * .5f + sin(x * 3.9f + seed * 2.1f) * .3f + sin(x * 8.3f + seed * .7f) * .2f).toFloat()

    private fun paintSignatureArt(c: Canvas, w: Float, h: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val light = theme.layout == ClusterTheme.Layout.SIG_SERENITY
        // Base: a dark (or misty) wash the art sits on.
        p.shader = LinearGradient(0f, 0f, 0f, h,
            if (light) intArrayOf(Color.argb(235, 214, 226, 240), Color.argb(225, 236, 242, 250), Color.argb(235, 210, 222, 238))
            else intArrayOf(Color.argb(235, 4, 7, 15), Color.argb(215, 6, 10, 20), Color.argb(240, 3, 5, 11)),
            null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        fun ribbons(colours: IntArray, top: Boolean, count: Int) {
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
            for (i in 0 until count) {
                val k = i / count.toFloat()
                val base = if (top) (.05f + k * .16f) * h else (.80f + k * .16f) * h
                val path = Path().apply {
                    moveTo(-.05f * w, base + sigNoise(i, 0f) * .06f * h)
                    cubicTo(.3f * w, base + sigNoise(i, 1f) * .12f * h, .65f * w, base + sigNoise(i, 2f) * .12f * h, 1.05f * w, base + sigNoise(i, 3f) * .06f * h)
                }
                p.strokeWidth = (.006f + .03f * ((i * 37) % 7) / 7f) * h
                p.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(withAlpha(colours[0], 40 + i * 12), withAlpha(colours[1], 70 + i * 14),
                    withAlpha(colours[2], 40 + i * 10)), null, Shader.TileMode.CLAMP)
                c.drawPath(path, p)
            }
            p.shader = null; p.style = Paint.Style.FILL
        }
        fun mountains(dusk: Boolean) {
            // Sky glow behind the ridges.
            p.shader = LinearGradient(0f, 0f, 0f, .45f * h,
                if (dusk) intArrayOf(Color.argb(230, 24, 18, 52), Color.argb(220, 120, 60, 90), Color.argb(200, 238, 128, 72))
                else intArrayOf(Color.argb(230, 226, 234, 246), Color.argb(220, 206, 220, 238), Color.argb(200, 236, 242, 250)),
                null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, .45f * h, p); p.shader = null
            for (layer in 0 until 4) {
                val y0 = (.24f + layer * .07f) * h
                val path = Path().apply {
                    moveTo(0f, h)
                    var x = 0f
                    lineTo(0f, y0)
                    while (x <= w) {
                        val peak = kotlin.math.abs(sigNoise(layer * 3 + 1, x / w * 6f)) * (.16f - layer * .025f) * h
                        lineTo(x, y0 - peak); x += w / 90f
                    }
                    lineTo(w, h); close()
                }
                p.color = if (dusk) Color.argb(235, 18 + layer * 6, 16 + layer * 5, 30 + layer * 8)
                    else Color.argb(150 + layer * 20, 150 - layer * 18, 170 - layer * 16, 196 - layer * 14)
                c.drawPath(path, p)
            }
        }
        when (theme.layout) {
            ClusterTheme.Layout.SIG_AURORA_EVO, ClusterTheme.Layout.MAP_AURORA -> {
                val colours = intArrayOf(Color.rgb(90, 60, 255), Color.rgb(140, 90, 255), Color.rgb(40, 150, 255))
                ribbons(colours, true, 9); ribbons(colours, false, 9)
            }
            ClusterTheme.Layout.SIG_HORIZON -> mountains(dusk = true)
            ClusterTheme.Layout.SIG_SERENITY -> mountains(dusk = false)
            ClusterTheme.Layout.SIG_NEXUS -> {
                val vx = .5f * w; val vy = .48f * h
                p.style = Paint.Style.STROKE
                for (i in 0 until 40) {
                    val a = Math.toRadians(i * 9.0)
                    val r = w * 1.1f
                    p.strokeWidth = (if (i % 5 == 0) 2.4f else 1.2f) * h / 720f
                    p.color = withAlpha(theme.accent, if (i % 5 == 0) 120 else 55)
                    c.drawLine(vx, vy, vx + r * cos(a).toFloat(), vy + r * sin(a).toFloat() * .45f, p)
                }
                for (k in 1..6) {
                    val f = k / 6f
                    p.strokeWidth = 1.6f * h / 720f; p.color = withAlpha(theme.accent, 40 + k * 14)
                    c.drawRoundRect(RectF(vx - f * .62f * w, vy - f * .50f * h, vx + f * .62f * w, vy + f * .52f * h), .04f * h, .04f * h, p)
                }
                p.style = Paint.Style.FILL
            }
            ClusterTheme.Layout.SIG_TERRAIN -> {
                p.style = Paint.Style.STROKE
                for ((cx, cy, n) in listOf(Triple(.22f, .30f, 14), Triple(.70f, .70f, 12), Triple(.92f, .20f, 9))) {
                    for (k in 1..n) {
                        val path = Path()
                        for (step in 0..72) {
                            val a = step / 72f * 2f * Math.PI.toFloat()
                            val r = (k * .028f * h) * (1f + .18f * sigNoise(k, a * 2f))
                            val x = cx * w + r * 1.6f * cos(a); val y = cy * h + r * sin(a)
                            if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        p.strokeWidth = 1.3f * h / 720f; p.color = withAlpha(theme.accent, 34 + (k % 4) * 16)
                        c.drawPath(path, p)
                    }
                }
                p.style = Paint.Style.FILL
            }
            ClusterTheme.Layout.SIG_VELOCITY -> {
                p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
                for (i in 0 until 46) {
                    val y = (.12f + (i * 53 % 46) / 46f * .78f) * h
                    val len = (.12f + (i * 29 % 13) / 13f * .25f) * w
                    val left = i % 2 == 0
                    p.strokeWidth = (1f + (i % 4)) * h / 720f
                    p.shader = LinearGradient(if (left) 0f else w, 0f, if (left) len else w - len, 0f,
                        withAlpha(theme.accent, 150), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    if (left) c.drawLine(0f, y, len, y + (y - .5f * h) * .1f, p) else c.drawLine(w, y, w - len, y + (y - .5f * h) * .1f, p)
                }
                p.shader = null; p.style = Paint.Style.FILL
            }
            ClusterTheme.Layout.SIG_NEBULA -> {
                for ((x, y, r, colour) in listOf(
                    listOf(.12f, .25f, .45f, Color.rgb(120, 40, 200)), listOf(.30f, .85f, .40f, Color.rgb(40, 60, 210)),
                    listOf(.85f, .20f, .40f, Color.rgb(200, 50, 170)), listOf(.92f, .80f, .45f, Color.rgb(70, 40, 190)),
                    listOf(.60f, .05f, .30f, Color.rgb(60, 120, 230)))) {
                    p.shader = RadialGradient((x as Float) * w, (y as Float) * h, (r as Float) * h,
                        intArrayOf(withAlpha(colour as Int, 150), withAlpha(colour, 50), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                    c.drawRect(0f, 0f, w, h, p)
                }
                p.shader = null
                var seed = 71
                for (i in 0 until 160) {
                    seed = seed * 1103515245 + 12345
                    val x = ((seed ushr 8) and 0xFFFF) / 65535f * w
                    seed = seed * 1103515245 + 12345
                    val y = ((seed ushr 8) and 0xFFFF) / 65535f * h
                    p.color = Color.argb(60 + (seed and 0x7F), 255, 255, 255)
                    c.drawCircle(x, y, (.6f + (i % 3) * .5f) * h / 720f, p)
                }
            }
            ClusterTheme.Layout.SIG_CRYSTAL -> {
                var seed = 19
                fun rnd(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xFFFF) / 65535f }
                val cols = 9; val rows = 4
                val pts = Array(rows + 1) { r -> Array(cols + 1) { col ->
                    PointF(col * w / cols + (if (col in 1 until cols) (rnd() - .5f) * w / cols * .6f else 0f),
                        r * h / rows + (if (r in 1 until rows) (rnd() - .5f) * h / rows * .6f else 0f)) } }
                for (r in 0 until rows) for (col in 0 until cols) {
                    val a = pts[r][col]; val b = pts[r][col + 1]; val cc = pts[r + 1][col + 1]; val d = pts[r + 1][col]
                    for (tri in listOf(listOf(a, b, cc), listOf(a, cc, d))) {
                        val path = Path().apply { moveTo(tri[0].x, tri[0].y); lineTo(tri[1].x, tri[1].y); lineTo(tri[2].x, tri[2].y); close() }
                        val shade = rnd()
                        p.shader = LinearGradient(tri[0].x, tri[0].y, tri[2].x, tri[2].y,
                            Color.argb(170, (60 + shade * 90).toInt(), (90 + shade * 100).toInt(), (130 + shade * 110).toInt()),
                            Color.argb(150, 20, 34, 58), Shader.TileMode.CLAMP)
                        c.drawPath(path, p)
                        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 1.1f * h / 720f; p.color = Color.argb(70, 220, 236, 255)
                        c.drawPath(path, p); p.style = Paint.Style.FILL
                    }
                }
            }
            ClusterTheme.Layout.MAP_PANORAMIC -> {
                for (top in listOf(true, false)) {
                    val edge = Path().apply {
                        if (top) { moveTo(0f, .17f * h); cubicTo(.3f * w, .08f * h, .7f * w, .08f * h, w, .17f * h) }
                        else { moveTo(0f, .80f * h); cubicTo(.3f * w, .90f * h, .7f * w, .90f * h, w, .80f * h) }
                    }
                    val band = Path(edge).apply { if (top) { lineTo(w, 0f); lineTo(0f, 0f) } else { lineTo(w, h); lineTo(0f, h) }; close() }
                    p.color = Color.argb(240, 3, 6, 14); c.drawPath(band, p)
                    p.style = Paint.Style.STROKE
                    p.color = withAlpha(theme.accent, 70); p.strokeWidth = 12f * h / 720f; c.drawPath(edge, p)
                    p.color = theme.accent; p.strokeWidth = 2.6f * h / 720f; c.drawPath(edge, p)
                    p.style = Paint.Style.FILL
                }
            }
            ClusterTheme.Layout.MAP_FOCUS -> {
                p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
                for (left in listOf(true, false)) {
                    val oval = if (left) RectF(.01f * w, .08f * h, .01f * w + .9f * h, .92f * h) else RectF(.99f * w - .9f * h, .08f * h, .99f * w, .92f * h)
                    val start = if (left) 130f else -50f
                    p.color = withAlpha(theme.accent, 60); p.strokeWidth = 14f * h / 720f; c.drawArc(oval, start, 100f, false, p)
                    p.color = theme.accent; p.strokeWidth = 3f * h / 720f; c.drawArc(oval, start, 100f, false, p)
                }
                p.style = Paint.Style.FILL
            }
            else -> Unit
        }
    }

    private fun sigHeader(canvas: Canvas, w: Float, h: Float, colour: Int) {
        snapshot?.outsideTempC?.let { studioText(canvas, "${it.roundToInt()}°C", .035f * w, .085f * h, .045f * h, colour, 500) }
        studioText(canvas, SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()), .965f * w, .085f * h, .045f * h, colour, 500,
            align = Paint.Align.RIGHT)
    }

    private fun sigFooter(canvas: Canvas, w: Float, h: Float, colour: Int, secondary: Int) {
        val y = .935f * h
        studioText(canvas, "ODO", .035f * w, y, .032f * h, secondary, 500)
        val odo = snapshot?.odometerKm?.let { NumberFormat.getIntegerInstance().format(it.roundToInt()) } ?: "—"
        studioText(canvas, "$odo km", .075f * w, y, .042f * h, colour, 500)
        // Fuel: pump, range, a long bar and the percentage, bottom right.
        drawPump(canvas, .70f * w, y - .045f * h, .045f * h)
        studioText(canvas, snapshot?.rangeKm?.let { "${it.roundToInt()} km" } ?: "— km", .74f * w, y - .012f * h, .042f * h, colour, 500)
        studioText(canvas, snapshot?.fuelPercent?.let { "${it.roundToInt()}%" } ?: "—", .965f * w, y - .012f * h, .036f * h, colour, 500,
            align = Paint.Align.RIGHT)
        val bar = RectF(.74f * w, y + .008f * h, .965f * w, y + .022f * h)
        fill.shader = null; fill.color = withAlpha(secondary, 70); canvas.drawRoundRect(bar, bar.height() / 2, bar.height() / 2, fill)
        snapshot?.fuelPercent?.let { f ->
            fill.color = theme.charge
            canvas.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * (f / 100f).coerceIn(0f, 1f), bar.bottom), bar.height() / 2, bar.height() / 2, fill)
        }
    }

    private fun sigSpeed(canvas: Canvas, x: Float, y: Float, size: Float, colour: Int, secondary: Int) {
        studioText(canvas, snapshot?.speedKph?.roundToInt()?.toString() ?: "—", x, y, size, colour, 600, align = Paint.Align.CENTER)
        studioText(canvas, "km/h", x, y + size * .32f, size * .2f, secondary, 500, align = Paint.Align.CENTER)
    }

    /** A dial: dark glass face, a lit rim and a speed or power scale. */
    private fun sigDial(canvas: Canvas, cx: Float, cy: Float, r: Float, rim: IntArray) {
        val s = height / 720f
        fill.shader = RadialGradient(cx, cy, r, intArrayOf(Color.argb(235, 10, 16, 28), Color.argb(250, 3, 6, 12)), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, fill); fill.shader = null
        line.shader = SweepGradient(cx, cy, rim, null)
        line.strokeWidth = 12f * s; line.alpha = 80; canvas.drawCircle(cx, cy, r, line)
        line.alpha = 255; line.strokeWidth = 3f * s; canvas.drawCircle(cx, cy, r, line)
        line.shader = null
    }

    private fun drawSignature(canvas: Canvas, w: Float, h: Float, showMusic: Boolean) {
        signatureArt(width, height)?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        val nav = nineNav()
        val showCard = nineShowsMusic(showMusic)
        val light = theme.layout == ClusterTheme.Layout.SIG_SERENITY
        val text = theme.text
        val secondary = theme.secondary
        val gold = intArrayOf(Color.rgb(120, 84, 30), Color.rgb(250, 210, 140), Color.rgb(160, 110, 40), Color.rgb(255, 230, 170), Color.rgb(120, 84, 30))
        when (theme.layout) {
            // Map-first frames: speed on the left, directions top centre, music on the right.
            ClusterTheme.Layout.MAP_FOCUS, ClusterTheme.Layout.MAP_PANORAMIC, ClusterTheme.Layout.MAP_AURORA -> {
                sigSpeed(canvas, .15f * w, .43f * h, .22f * h, text, secondary)
                chargePowerBar(canvas, .06f * w, .24f * w, .62f * h)
                nav?.let { glassNav(canvas, RectF(.53f * w, .12f * h, .72f * w, .34f * h), it) }
                if (showCard) glassMusic(canvas, RectF(.745f * w, .24f * h, .955f * w, .64f * h))
            }
            ClusterTheme.Layout.MAP_GLASS -> {
                val panel = Path().apply {
                    moveTo(.02f * w, .22f * h); cubicTo(.02f * w, .14f * h, .05f * w, .12f * h, .12f * w, .12f * h)
                    lineTo(.27f * w, .12f * h); cubicTo(.34f * w, .30f * h, .36f * w, .55f * h, .40f * w, .78f * h)
                    lineTo(.06f * w, .80f * h); cubicTo(.03f * w, .80f * h, .02f * w, .76f * h, .02f * w, .70f * h); close()
                }
                nineGlass(canvas, panel, .12f * h, .80f * h, withAlpha(theme.accent, 220), glow = true)
                sigSpeed(canvas, .15f * w, .44f * h, .22f * h, text, secondary)
                chargePowerBar(canvas, .06f * w, .25f * w, .64f * h)
                nav?.let { glassNav(canvas, RectF(.45f * w, .13f * h, .64f * w, .34f * h), it) }
                if (showCard) glassMusic(canvas, RectF(.70f * w, .22f * h, .955f * w, .66f * h))
            }
            ClusterTheme.Layout.MAP_TRIPLE_DIAL -> {
                val r = .36f * h
                sigDial(canvas, .17f * w, .48f * h, r, gold)
                sigDial(canvas, .83f * w, .48f * h, r, gold)
                sigSpeed(canvas, .17f * w, .50f * h, .22f * h, text, secondary)
                nav?.let { glassNav(canvas, RectF(.42f * w, .12f * h, .60f * w, .34f * h), it) }
                if (showCard) glassMusic(canvas, RectF(.83f * w - r * .78f, .48f * h - r * .62f, .83f * w + r * .78f, .48f * h + r * .66f))
            }
            ClusterTheme.Layout.MAP_COCKPIT -> {
                val left = Path().apply {
                    moveTo(.10f * w, .12f * h); lineTo(.30f * w, .12f * h); lineTo(.36f * w, .45f * h); lineTo(.30f * w, .80f * h)
                    lineTo(.10f * w, .80f * h); lineTo(.03f * w, .46f * h); close()
                }
                nineGlass(canvas, left, .12f * h, .80f * h, withAlpha(theme.accent, 230), glow = true)
                sigSpeed(canvas, .19f * w, .45f * h, .22f * h, text, secondary)
                chargePowerBar(canvas, .10f * w, .29f * w, .66f * h)
                nav?.let { glassNav(canvas, RectF(.44f * w, .13f * h, .63f * w, .34f * h), it) }
                if (showCard) {
                    val box = RectF(.70f * w, .26f * h, .955f * w, .68f * h)
                    val shape = Path().apply {
                        moveTo(box.left + .02f * w, box.top); lineTo(box.right - .02f * w, box.top); lineTo(box.right, box.centerY())
                        lineTo(box.right - .02f * w, box.bottom); lineTo(box.left + .02f * w, box.bottom); lineTo(box.left, box.centerY()); close()
                    }
                    glassMusic(canvas, box, path = shape)
                }
            }
            // Scene themes: speed in the centre, directions on the left, music on the right.
            ClusterTheme.Layout.SIG_METRO -> {
                sigSpeed(canvas, .12f * w, .45f * h, .22f * h, text, secondary)
                nav?.let { glassNav(canvas, RectF(.24f * w, .12f * h, .46f * w, .34f * h), it) }
                if (showCard) glassMusic(canvas, RectF(.745f * w, .24f * h, .955f * w, .66f * h))
            }
            ClusterTheme.Layout.SIG_CLASSIC -> {
                val r = .40f * h
                sigDial(canvas, .15f * w, .52f * h, r, intArrayOf(theme.accent, theme.secondary, theme.accent))
                cockpitScale(canvas, .15f * w, .52f * h, r - .03f * h, 135f, 270f, 220)
                sigSpeed(canvas, .15f * w, .58f * h, .17f * h, text, secondary)
                sigDial(canvas, .85f * w, .52f * h, r, intArrayOf(theme.accent, theme.secondary, theme.accent))
                // Power above, charge below, on the right dial.
                val oval = RectF(.85f * w - r + .05f * h, .52f * h - r + .05f * h, .85f * w + r - .05f * h, .52f * h + r - .05f * h)
                val f = kwNow()?.let { (it / GwmAndroidAutoClusterView.BATTERY_FULL_SCALE_KW).coerceIn(-1f, 1f) } ?: 0f
                line.shader = null; line.strokeCap = Paint.Cap.ROUND; line.strokeWidth = .02f * h
                line.color = Color.argb(150, 40, 54, 76); canvas.drawArc(oval, 150f, 240f, false, line)
                line.color = theme.charge; if (kotlin.math.abs(f) > .01f) canvas.drawArc(oval, 270f, 120f * f, false, line)
                studioText(canvas, "POWER", .85f * w, .40f * h, .034f * h, secondary, 600, align = Paint.Align.CENTER)
                studioText(canvas, "CHARGE", .85f * w, .66f * h, .034f * h, secondary, 600, align = Paint.Align.CENTER)
                nav?.let { glassNav(canvas, RectF(.41f * w, .10f * h, .59f * w, .32f * h), it) }
            }
            ClusterTheme.Layout.SIG_TERRAIN -> {
                sigSpeed(canvas, .50f * w, .36f * h, .20f * h, text, secondary)
                nav?.let { glassNav(canvas, RectF(.04f * w, .28f * h, .27f * w, .52f * h), it) }
                // Two compass rings on the right, as on the sheet, marked with the cardinal points.
                for ((cx, cy, r) in listOf(Triple(.78f * w, .34f * h, .12f * h), Triple(.90f * w, .56f * h, .10f * h))) {
                    line.shader = null; line.color = withAlpha(theme.accent, 200); line.strokeWidth = 2.4f * h / 720f
                    canvas.drawCircle(cx, cy, r, line)
                    for (i in 0 until 36) {
                        val a = Math.toRadians(i * 10.0)
                        val r1 = r * (if (i % 9 == 0) .78f else .88f)
                        line.strokeWidth = (if (i % 9 == 0) 2.2f else 1f) * h / 720f
                        canvas.drawLine(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(), line)
                    }
                    studioText(canvas, "N", cx, cy - r * .45f, .03f * h, theme.accent, 700, align = Paint.Align.CENTER)
                }
            }
            else -> {
                // Onyx, Luxe, Velocity and Nebula ring the speed; the rest leave it bare.
                val cx = .50f * w; val cy = .42f * h
                when (theme.layout) {
                    ClusterTheme.Layout.SIG_ONYX -> nineRing(canvas, cx, cy, .36f * h,
                        intArrayOf(withAlpha(theme.accent, 40), theme.accent, Color.WHITE, theme.accent, withAlpha(theme.accent, 40)), start = 140f, sweep = 260f)
                    ClusterTheme.Layout.SIG_LUXE -> {
                        fill.shader = RadialGradient(cx, cy, .42f * h, intArrayOf(Color.argb(90, 250, 190, 90), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                        canvas.drawCircle(cx, cy, .42f * h, fill); fill.shader = null
                        sigDial(canvas, cx, cy, .33f * h, gold)
                    }
                    ClusterTheme.Layout.SIG_VELOCITY -> {
                        cockpitScale(canvas, cx, cy + .04f * h, .38f * h, 150f, 240f, 240, labelsInside = true)
                    }
                    ClusterTheme.Layout.SIG_NEBULA -> {
                        line.shader = null; line.color = withAlpha(theme.accent, 60); line.strokeWidth = 14f * h / 720f
                        canvas.drawCircle(cx, cy + .02f * h, .40f * h, line)
                        line.color = withAlpha(theme.text, 180); line.strokeWidth = 2f * h / 720f
                        canvas.drawCircle(cx, cy + .02f * h, .40f * h, line)
                    }
                    else -> Unit
                }
                sigSpeed(canvas, cx, cy + .05f * h, .21f * h, text, secondary)
                nav?.let { glassNav(canvas, RectF(.04f * w, .30f * h, .27f * w, .54f * h), it) }
                if (showCard) glassMusic(canvas, RectF(.735f * w, .22f * h, .96f * w, .64f * h))
            }
        }
        sigHeader(canvas, w, h, text)
        sigFooter(canvas, w, h, text, secondary)
    }

