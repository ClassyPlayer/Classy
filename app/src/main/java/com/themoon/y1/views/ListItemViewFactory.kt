package com.themoon.y1.views

import android.content.Context
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.themoon.y1.ThemeManager

object ListItemViewFactory {
    /** The text size that gives the height of the arrow view ([ClassyLists.arrow]). The rows have [ClassyMetrics.LIST_TEXT_SP]. */
    const val LIST_TEXT_SIZE = 21f

    /** Space between the text (or the value) of a row and its arrow ([ClassyLists.arrow]). */
    private const val ARROW_MARGIN_DP = 10

    fun createButtonBackground(color: Int): GradientDrawable = GradientDrawable().apply { setColor(color) }

    /** The blue gradient of the selected row. */
    fun createFocusedButtonBackground(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0xFF62B7F5.toInt(), 0xFF2588DC.toInt(), 0xFF0865C4.toInt())
    )

    fun createListButtonWithIcon(
        context: Context,
        iconUnicode: String,
        textLabel: String,
        customColor: Int = 0,
        /** Called when the row gets the focus. */
        onFocused: (() -> Unit)? = null
    ): View {
        val d = context.resources.displayMetrics.density
        val rowButton = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isFocusable = true
            isClickable = true
            isSoundEffectsEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val normalColor = if (customColor != 0) customColor else ThemeManager.getTextColorPrimary()

        val tvIcon = TextView(context).apply {
            text = iconUnicode
            textSize = 21f
            val iconLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = (15 * d).toInt()
            }
            layoutParams = iconLp
            visibility = View.GONE
        }

        val tvText = TextView(context).apply {
            text = textLabel
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isHorizontalFadingEdgeEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvArrow = ClassyLists.arrow(context, ARROW_MARGIN_DP)

        rowButton.addView(tvIcon)
        rowButton.addView(tvText)
        rowButton.addView(tvArrow)
        ClassyLists.styleRow(rowButton, tvText)
        menuRowLook(rowButton, normalColor) { if (it) onFocused?.invoke() }
        return rowButton
    }

    /**
     * The focus look of a [createListButtonWithIcon] row: the icon and the text in [normalColor], the arrow on the
     * selected row. Also for a recycled row with another colour or other actions ([onFocus]).
     */
    fun menuRowLook(row: LinearLayout, normalColor: Int, onFocus: ((Boolean) -> Unit)? = null): FocusLook {
        val text = row.getChildAt(1) as TextView
        val look = FocusLook.of(row).reset()
            .text(row.getChildAt(0) as TextView) { normalColor }
            .text(text) { normalColor }
        look.marquee = text
        look.arrow = row.getChildAt(2)
        if (onFocus != null) look.onFocus(onFocus)
        return look.refresh()
    }

    /**
     * An audiobook or podcast row with a saved position: the background shows the progress (the row keeps its
     * texts; no arrow).
     */
    fun setupAudiobookProgress(btn: View, pos: Int, dur: Int) {
        if (pos <= 0 || dur <= 0) return
        val look = FocusLook.of(btn).reset()
        val texts = when {
            btn is TextView -> listOf(btn)
            btn is LinearLayout && btn.childCount > 1 -> listOfNotNull(btn.getChildAt(0) as? TextView, btn.getChildAt(1) as? TextView)
            else -> emptyList()
        }
        for (tv in texts) look.text(tv)
        look.progress = intArrayOf(pos, dur)
        look.refresh()
    }

    fun applyProgressBackground(
        btn: View,
        currentMs: Int,
        totalMs: Int,
        isFocused: Boolean
    ) {
        if (currentMs <= 0 || totalMs <= 0) return
        var progressPercent = ((currentMs.toFloat() / totalMs) * 10000).toInt()
        if (progressPercent > 10000) progressPercent = 10000

        val baseColor = if (isFocused) ThemeManager.getListButtonFocusedBg() else ThemeManager.getListButtonNormalBg()
        val baseBg = createButtonBackground(baseColor)

        val progressColor = if (isFocused) 0x66FFFFFF.toInt() else ((ThemeManager.getListButtonFocusedBg() and 0x00FFFFFF) or 0x44000000.toInt())
        val progressBg = createButtonBackground(progressColor)

        val clipProgress = ClipDrawable(progressBg, Gravity.LEFT, ClipDrawable.HORIZONTAL)
        clipProgress.level = progressPercent

        val layerBg = LayerDrawable(arrayOf(baseBg, clipProgress))

        val pLeft = btn.paddingLeft
        val pTop = btn.paddingTop
        val pRight = btn.paddingRight
        val pBottom = btn.paddingBottom

        btn.background = layerBg
        btn.setPadding(pLeft, pTop, pRight, pBottom)
    }

    /** A row that is one button: its text in the bold list text, the capitals on the centre line of the row. */
    fun createListButtonSimple(context: Context, text: String): Button {
        val d = context.resources.displayMetrics.density
        val rowHeight = ClassyLayout.px(ClassyMetrics.ROW_HEIGHT, d)
        val btn = Button(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ClassyMetrics.LIST_TEXT_SP * d)
            typeface = ThemeManager.getCustomFontBold()
            includeFontPadding = false
            gravity = Gravity.LEFT or Gravity.TOP
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight)
        }
        btn.setPadding(ClassyLayout.px(ClassyMetrics.ROW_PADDING_LEFT, d), ClassyLayout.capCentredPaddingTop(btn, rowHeight.toFloat()),
            ClassyLayout.px(ClassyMetrics.ROW_PADDING_RIGHT, d), 0)
        FocusLook.ofButton(btn).refresh()
        return btn
    }

    /** A Settings row: the title, the value on the right and, for a row that [opens] a page, the arrow on the selected row. */
    fun createSettingRow(context: Context, title: String, value: String, opens: Boolean = false): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isFocusable = true
            isClickable = true
        }

        val tvTitle = TextView(context).apply {
            text = title
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        row.addView(tvTitle)

        // Shows an on/off value as a switch when Settings → Toggle Style is Switch.
        val tvVal = SettingValueView(context).apply {
            stateKey = title
            text = value
            typeface = ThemeManager.getCustomFont()
        }
        row.addView(tvVal)
        // A row that opens a deeper screen: the arrow of the other lists, only on the selected row (the value keeps its place)
        val look = FocusLook.of(row).text(tvTitle).text(tvVal) { ThemeManager.getTextColorSecondary() }
        if (opens) look.arrow = ClassyLists.arrow(context, ARROW_MARGIN_DP).also { row.addView(it) }
        look.refresh()
        ClassyLists.styleRow(row, tvTitle, tvVal)
        return row
    }

    /**
     * A text on the left of the value of a setting row ([createSettingRow]; the time left of Bluetooth → Discoverable),
     * in the text style and colour of the value, with its capitals on the centre line of the row. GONE until the caller
     * gives it a text.
     */
    fun addValueNote(row: LinearLayout): TextView {
        val value = row.getChildAt(1) as TextView
        val note = TextView(row.context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, value.textSize)
            typeface = value.typeface
            setTextColor(value.currentTextColor)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            gravity = Gravity.TOP or Gravity.END
            setPadding(0, ClassyLayout.capCentredPaddingTop(this, row.layoutParams.height.toFloat()),
                (8 * row.resources.displayMetrics.density).toInt(), 0)
            visibility = View.GONE
        }
        row.addView(note, 1)
        // The row changes the value colour when it gets or loses the focus: the note gets the same colour
        FocusLook.of(row).onFocus { note.setTextColor(value.currentTextColor) }
        return note
    }

    fun createCategoryHeader(context: Context, container: LinearLayout, title: String) {
        val tv = TextView(context).apply {
            text = title
            setTextColor(ThemeManager.getTextColorSecondary())
            textSize = 12f
            gravity = Gravity.CENTER
            val d = context.resources.displayMetrics.density
            setPadding(0, (8 * d).toInt(), 0, (4 * d).toInt())
        }
        container.addView(tv)
    }

    /** A row with an icon on the left; [color] (0 = the theme colour) is the colour of the icon and the text. */
    fun createTablerListButton(context: Context, icon: Array<String>?, text: String, color: Int = 0): View =
        createTablerListButtonWithRightIcon(context, icon, text, iconColor = color, textColor = color)

    /**
     * A row of a single-choice list (Theme, Language, EQ preset...). Each row keeps the tick column on the
     * left, so the text does not move; only the chosen row shows the tick ([CheckMarkView]). Normal text and
     * focus colours.
     */
    fun createCheckListButton(context: Context, text: String, isChecked: Boolean): View {
        val row = createTablerListButton(context, TablerIcons.CHECK, text)
        if (!isChecked) (row as ViewGroup).getChildAt(0).visibility = View.INVISIBLE
        return row
    }

    /**
     * A row of a menu editor (Settings → Menus → Main Menu Items): the label and an eye ([shown]) or a grey cross on
     * the right.
     */
    fun createShownRow(context: Context, text: String, shown: Boolean): View =
        createTablerListButtonWithRightIcon(context, null, text, if (shown) TablerIcons.EYE else TablerIcons.X,
            rightIconColor = if (shown) ThemeManager.getTextColorPrimary() else 0)

    fun createTablerListButtonWithRightIcon(
        context: Context,
        iconPaths: Array<String>?,
        text: String,
        rightIconPaths: Array<String>? = null,
        iconColor: Int = 0,
        textColor: Int = 0,
        /** The colour of the right icon on a row that is not selected; 0 = the secondary text colour. */
        rightIconColor: Int = 0
    ): View {
        val d = context.resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isFocusable = true
            isClickable = true
        }

        val actualIconColor = if (iconColor != 0) iconColor else ThemeManager.getTextColorPrimary()
        val actualTextColor = if (textColor != 0) textColor else ThemeManager.getTextColorPrimary()
        val actualRightIconColor = if (rightIconColor != 0) rightIconColor else ThemeManager.getTextColorSecondary()

        val iconSize = (18f * d).toInt()
        var ivLeft: ImageView? = null
        // The tick of a selection list: the same column, but as tall as the capital letters of the text.
        val tick = if (iconPaths === TablerIcons.CHECK) CheckMarkView(context) else null
        if (tick != null) {
            row.addView(tick)
        } else if (iconPaths != null) {
            ivLeft = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    rightMargin = (10f * d).toInt()
                }
                setImageBitmap(TablerIcons.render(iconPaths, iconSize, actualIconColor))
            }
            row.addView(ivLeft)
        }

        // A long name ends with "…" (it does not scroll on the selected row)
        val tv = TextView(context).apply {
            this.text = text
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        row.addView(tv)
        tick?.textView = tv

        // As tall as the row text: the eye and the cross of the menu editors fill a 24 dp box only in part
        val rightIconSize = (24f * d).toInt()
        var ivRight: ImageView? = null
        if (rightIconPaths != null) {
            ivRight = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(rightIconSize, rightIconSize).apply {
                    leftMargin = (8f * d).toInt()
                }
                setImageBitmap(TablerIcons.render(rightIconPaths, rightIconSize, actualRightIconColor))
            }
            row.addView(ivRight)
        }

        val look = FocusLook.of(row).text(tv) { actualTextColor }
        look.onFocus { focused ->
            val focusedColor = ThemeManager.getListButtonFocusedTextColor()
            tick?.color = if (focused) focusedColor else actualIconColor
            if (iconPaths != null) ivLeft?.setImageBitmap(TablerIcons.render(iconPaths, iconSize, if (focused) focusedColor else actualIconColor))
            if (rightIconPaths != null) ivRight?.setImageBitmap(TablerIcons.render(rightIconPaths, rightIconSize,
                if (focused) focusedColor else actualRightIconColor))
        }.refresh()
        ClassyLists.styleRow(row, tv)
        return row
    }

    /**
     * A row of the Wi-Fi and Bluetooth lists, like macOS: a tick column on the left (the tick shows only on the
     * chosen row, so the text does not move), the name, then status glyphs in fixed slots on the right (lock, signal,
     * device type; [RowGlyphsView]). The tick column has the same size as the single-choice lists. The glyphs are as
     * tall as the capital letters of the name and on its baseline, in the primary text colour (as macOS: black or
     * white, not grey), and in the focused text colour on the focused row.
     */
    fun createChoiceRowWithRightIcons(
        context: Context,
        text: String,
        isChecked: Boolean,
        slots: List<RowGlyphs.Slot>
    ): View {
        val row = createTablerListButtonWithRightIcon(context, TablerIcons.CHECK, text) as LinearLayout
        if (!isChecked) row.getChildAt(0).visibility = View.INVISIBLE

        val glyphs = RowGlyphsView(context, slots).apply {
            textView = row.getChildAt(1) as TextView
            color = ThemeManager.getTextColorPrimary()
        }
        row.addView(glyphs)
        FocusLook.of(row).onFocus { glyphs.color = if (it) ThemeManager.getListButtonFocusedTextColor() else ThemeManager.getTextColorPrimary() }
        return row
    }
}
