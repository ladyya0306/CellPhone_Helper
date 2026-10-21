package com.example.llama

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 记忆管理页：它记住了什么、正在猜什么、你否掉了什么。
 *   · 待确认：从聊天里发现的兴趣/职业/宠物等候选，点「记住」才写入，点「忽略」以后不再提；说两次也会自动转正
 *   · 已记住：每条都能改、能删（改 = 删旧写新）
 *   · 已忽略：可以一键恢复（清空忽略名单）
 * 布局沿用设置页的外壳，内容用代码构建。
 */
class MemoryActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private lateinit var memory: MemoryStore
    private lateinit var candidates: CandidateStore
    private val sk: Skin get() = AppSettings(this).skin()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        memory = MemoryStore(this)
        candidates = CandidateStore(this)
        content = findViewById(R.id.content)
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = "记忆管理"
            setNavigationOnClickListener { finish() }
            SkinUi.applyChrome(this@MemoryActivity, sk, this)
        }
        build()
    }

    private fun build() {
        content.removeAllViews()
        val all = candidates.all()
        val pending = Candidates.pending(all).sortedByDescending { it.lastAt }
        val rejected = all.filter { it.status == "rejected" }

        title("待确认（${pending.size}）")
        if (pending.isEmpty()) hint("目前没有。聊天里提到「我喜欢…」「我是…」「我养了…」「我在学…」这类话时，我会先记在这里，等你确认。")
        else {
            val card = card()
            pending.forEachIndexed { i, c ->
                if (i > 0) card.addView(divider())
                card.addView(candidateRow(c))
            }
        }

        val facts = memory.allFacts()
        title("已记住（${facts.size}）")
        if (facts.isEmpty()) hint("还没有记住任何事。")
        else {
            val card = card()
            facts.forEachIndexed { i, (key, value) ->
                if (i > 0) card.addView(divider())
                card.addView(factRow(key, value))
            }
        }

        title("已忽略（${rejected.size}）")
        if (rejected.isNotEmpty()) {
            val card = card()
            card.addView(textRow("你忽略过 ${rejected.size} 条，我不会再提它们", "点这里恢复：清空忽略名单").apply {
                setOnClickListener {
                    MaterialAlertDialogBuilder(this@MemoryActivity).setTitle("清空忽略名单？")
                        .setMessage("之后聊天里再提到这些事，又会进待确认。")
                        .setPositiveButton("清空") { _, _ -> candidates.save(all.filter { it.status != "rejected" }); build() }
                        .setNegativeButton("取消", null).show()
                }
            })
        } else hint("没有。")
    }

    private fun candidateRow(c: Candidates.Cand): View {
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        ll.addView(TextView(this).apply { text = "${c.category}　${c.text}"; setTextColor(sk.text); textSize = 15f })
        ll.addView(TextView(this).apply {
            text = "你说过：「${c.evidence.take(40)}」· 提到 ${c.count} 次"
            setTextColor(sk.textDim); textSize = 12f; setPadding(0, dp(3), 0, dp(6))
        })
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        btns.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "忽略"; setOnClickListener { candidates.save(Candidates.reject(candidates.all(), c.id).list); build() }
        })
        btns.addView(MaterialButton(this).apply {
            text = "记住"
            setOnClickListener {
                val obs = Candidates.confirm(candidates.all(), c.id)
                candidates.save(obs.list)
                obs.promoted.forEach { memory.saveFact(it.text, it.evidence) }
                build()
            }
        })
        ll.addView(btns)
        return ll
    }

    private fun factRow(key: String, value: String): View {
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        ll.addView(TextView(this).apply { text = key; setTextColor(sk.text); textSize = 15f })
        if (value.isNotBlank() && value != key) ll.addView(TextView(this).apply {
            text = "来源：「${value.take(40)}」"; setTextColor(sk.textDim); textSize = 12f; setPadding(0, dp(3), 0, 0)
        })
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        btns.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "改"; setOnClickListener { editFact(key, value) }
        })
        btns.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "删"
            setOnClickListener {
                MaterialAlertDialogBuilder(this@MemoryActivity).setTitle("删除这条记忆？").setMessage(key)
                    .setPositiveButton("删除") { _, _ -> memory.forget(key); build() }
                    .setNegativeButton("取消", null).show()
            }
        })
        ll.addView(btns)
        return ll
    }

    private fun editFact(key: String, value: String) {
        val et = EditText(this).apply { setText(key); inputType = InputType.TYPE_CLASS_TEXT; setPadding(dp(20), dp(12), dp(20), dp(12)) }
        MaterialAlertDialogBuilder(this).setTitle("修改这条记忆").setView(et)
            .setPositiveButton("保存") { _, _ ->
                val nk = et.text.toString().trim()
                if (nk.isNotEmpty() && nk != key) { memory.forget(key); memory.saveFact(nk, value.ifBlank { nk }) }
                build()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun title(t: String) = content.addView(TextView(this).apply {
        text = t; setTextColor(sk.text); textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(dp(12), dp(20), dp(12), dp(4))
    })

    private fun hint(t: String) = content.addView(TextView(this).apply {
        text = t; setTextColor(sk.textDim); textSize = 12f; setPadding(dp(16), dp(6), dp(16), 0)
    })

    private fun card(): LinearLayout {
        val cv = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
            radius = dp(sk.cardRadiusDp).toFloat(); cardElevation = 0f
            setCardBackgroundColor(sk.card); strokeWidth = dp(1); strokeColor = sk.divider
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cv.addView(inner); content.addView(cv)
        return inner
    }

    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)); setBackgroundColor(sk.divider)
    }

    private fun textRow(t: String, sub: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(TextView(this@MemoryActivity).apply { text = t; setTextColor(sk.text); textSize = 15f })
        addView(TextView(this@MemoryActivity).apply { text = sub; setTextColor(sk.textDim); textSize = 12f; setPadding(0, dp(3), 0, 0) })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
