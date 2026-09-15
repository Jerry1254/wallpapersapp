package com.qingjing.wallpaper_android.playback

import com.qingjing.wallpaper_android.install.StrictJson

internal data class ParallaxLayer(
    val role: String,
    val ordinal: Int,
    val offsetPercent: Float,
    val scale: Float,
    val opacity: Float,
    val blend: String
)

internal data class ParallaxConfiguration(
    val width: Int,
    val height: Int,
    val maxAngle: Float,
    val smoothing: Float,
    val layers: List<ParallaxLayer>
) {
    companion object {
        fun parse(bytes: ByteArray): ParallaxConfiguration {
            require(bytes.size in 1..65536)
            fun fields(value: Any?,keys: Set<String>): Map<*,*> =
                (value as? Map<*,*> ?: error("Invalid configuration")).also { require(it.keys == keys) }
            fun number(value: Any?,min: Double,max: Double): Float =
                ((value as? Number)?.toDouble() ?: error("Invalid number")).also { require(it.isFinite() && it in min..max) }.toFloat()
            fun integer(value: Any?,min: Int,max: Int): Int =
                ((value as? Long) ?: error("Invalid integer")).also { require(it in min.toLong()..max.toLong()) }.toInt()

            val root=fields(StrictJson.parse(bytes),setOf("formatVersion","canvas","motion","layers"))
            require(integer(root["formatVersion"],2,2)==2)
            val canvas=fields(root["canvas"],setOf("width","height"))
            val motion=fields(root["motion"],setOf("maxAngle"))
            val input=root["layers"] as? List<*> ?: error("Invalid layers")
            require(input.size in 2..12)
            val source=input.mapIndexed { position,item ->
                val layer=fields(item,setOf("index","offsetPercent","scale","opacity","blendMode"))
                require(integer(layer["index"],1,12)==position+1)
                val blend=layer["blendMode"] as? String ?: error("Invalid blend")
                require(blend in setOf("normal","screen","add"))
                val background=position==input.lastIndex
                ParallaxLayer(
                    if(background) "BACKGROUND" else "FOREGROUND",
                    if(background) 0 else position,
                    number(layer["offsetPercent"],-25.0,25.0),
                    number(layer["scale"],1.0,1.5),
                    number(layer["opacity"],0.0,1.0),
                    blend
                )
            }
            return ParallaxConfiguration(
                integer(canvas["width"],512,4096),
                integer(canvas["height"],512,4096),
                number(motion["maxAngle"],1.0,75.0),
                .2f,
                source.reversed()
            )
        }
    }
}
