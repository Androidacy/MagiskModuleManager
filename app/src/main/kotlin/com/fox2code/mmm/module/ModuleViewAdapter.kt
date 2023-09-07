/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

@file:Suppress("ktConcatNullable")

package com.fox2code.mmm.module

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.cardview.widget.CardView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.fox2code.foxcompat.view.FoxDisplay
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.R
import com.fox2code.mmm.manager.ModuleInfo
import com.fox2code.mmm.manager.ModuleManager.Companion.instance
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.topjohnwu.superuser.internal.UiThreadHandler
import timber.log.Timber

class ModuleViewAdapter : RecyclerView.Adapter<ModuleViewAdapter.ViewHolder>() {
    @JvmField
    val moduleHolders = ArrayList<ModuleHolder>()
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.module_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val moduleHolder = moduleHolders[position]
        try {
            if (holder.update(moduleHolder)) {
                UiThreadHandler.handler.post {
                    if (moduleHolders[position] == moduleHolder) {
                        moduleHolders.removeAt(position)
                        notifyItemRemoved(position)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error while updating module holder. This may mean we're trying to update too early.")
        }
    }

    override fun getItemCount(): Int {
        return moduleHolders.size
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardView: CardView
        private val invalidPropsChip: Chip
        private val buttonAction: ImageButton
        private val switchMaterial: MaterialSwitch
        private val titleText: TextView
        private val creditText: TextView
        private val descriptionText: TextView
        private val moduleOptionsHolder: HorizontalScrollView
        private val moduleLayoutHelper: TextView
        private val updateText: TextView
        private val actionsButtons: Array<Chip?>
        private val actionButtonsTypes: ArrayList<ActionButtonType?>

        @Suppress("MemberVisibilityCanBePrivate")
        var moduleHolder: ModuleHolder? = null
        var background: Drawable
        private var initState = true

        init {
            cardView = itemView.findViewById(R.id.card_view)
            invalidPropsChip = itemView.findViewById(R.id.invalid_module_props)
            buttonAction = itemView.findViewById(R.id.button_action)
            switchMaterial = itemView.findViewById(R.id.switch_action)
            titleText = itemView.findViewById(R.id.title_text)
            creditText = itemView.findViewById(R.id.credit_text)
            descriptionText = itemView.findViewById(R.id.description_text)
            moduleOptionsHolder = itemView.findViewById(R.id.module_options_holder)
            moduleLayoutHelper = itemView.findViewById(R.id.module_layout_helper)
            updateText = itemView.findViewById(R.id.updated_text)
            actionsButtons = arrayOfNulls(6)
            actionsButtons[0] = itemView.findViewById(R.id.button_action1)
            actionsButtons[1] = itemView.findViewById(R.id.button_action2)
            actionsButtons[2] = itemView.findViewById(R.id.button_action3)
            actionsButtons[3] = itemView.findViewById(R.id.button_action4)
            actionsButtons[4] = itemView.findViewById(R.id.button_action5)
            actionsButtons[5] = itemView.findViewById(R.id.button_action6)
            background = cardView.background
            // Apply default
            cardView.setOnClickListener { v: View? ->
                val moduleHolder = moduleHolder
                if (moduleHolder != null) {
                    var onClickListener = moduleHolder.onClickListener
                    if (onClickListener != null) {
                        onClickListener.onClick(v)
                    } else if (moduleHolder.notificationType != null) {
                        onClickListener = moduleHolder.notificationType.onClickListener
                        onClickListener?.onClick(v)
                    }
                }
            }
            buttonAction.isClickable = false
            switchMaterial.isEnabled = false
            switchMaterial.setOnCheckedChangeListener { _: CompoundButton?, checked: Boolean ->
                if (initState) return@setOnCheckedChangeListener   // Skip if non user
                val moduleHolder = moduleHolder
                if (moduleHolder?.moduleInfo != null) {
                    val moduleInfo: ModuleInfo? = moduleHolder.moduleInfo
                    if (!instance?.setEnabledState(
                            moduleInfo!!, checked
                        )!!
                    ) {
                        switchMaterial.isChecked =
                            (moduleInfo?.flags ?: 0) and ModuleInfo.FLAG_MODULE_DISABLED == 0
                    }
                }
            }
            actionButtonsTypes = ArrayList()
            for (i in actionsButtons.indices) {
                actionsButtons[i]?.setOnClickListener(View.OnClickListener setOnClickListener@{ v: View? ->
                    if (initState) return@setOnClickListener   // Skip if non user
                    val moduleHolder = moduleHolder
                    if (i < actionButtonsTypes.size && moduleHolder != null) {
                        (v as Chip?)?.let { actionButtonsTypes[i]!!.doAction(it, moduleHolder) }
                        if (moduleHolder.shouldRemove()) {
                            cardView.visibility = View.GONE
                        }
                    }
                })
                actionsButtons[i]?.setOnLongClickListener(OnLongClickListener setOnLongClickListener@{ v: View? ->
                    if (initState) return@setOnLongClickListener false // Skip if non user
                    val moduleHolder = moduleHolder
                    var didSomething = false
                    if (i < actionButtonsTypes.size && moduleHolder != null) {
                        didSomething = (v as Chip?)?.let {
                            actionButtonsTypes[i]!!
                                .doActionLong(it, moduleHolder)
                        } == true
                        if (moduleHolder.shouldRemove()) {
                            cardView.visibility = View.GONE
                        }
                    }
                    didSomething
                })
            }
            initState = false
        }

        fun getString(@StringRes resId: Int): String {
            return itemView.context.getString(resId)
        }

        @SuppressLint("SetTextI18n")
        fun update(moduleHolder: ModuleHolder): Boolean {
            initState = true
            if (moduleHolder.isModuleHolder && moduleHolder.shouldRemove()) {
                cardView.visibility = View.GONE
                this.moduleHolder = null
                initState = false
                return true
            }
            val type = moduleHolder.type
            val vType = moduleHolder.getCompareType(type)
            cardView.visibility = View.VISIBLE
            val showCaseMode = MainApplication.isShowcaseMode
            if (moduleHolder.isModuleHolder) {
                buttonAction.visibility = View.GONE
                buttonAction.background = null
                val localModuleInfo = moduleHolder.moduleInfo
                if (localModuleInfo != null) {
                    localModuleInfo.verify()
                    switchMaterial.visibility = View.VISIBLE
                    switchMaterial.isChecked =
                        localModuleInfo.flags and ModuleInfo.FLAG_MODULE_DISABLED == 0
                } else {
                    switchMaterial.visibility = View.GONE
                }
                creditText.visibility = View.VISIBLE
                moduleOptionsHolder.visibility = View.VISIBLE
                moduleLayoutHelper.visibility = View.VISIBLE
                descriptionText.visibility = View.VISIBLE
                val moduleInfo = moduleHolder.mainModuleInfo
                moduleInfo.verify()
                moduleInfo.name.also { titleText.text = it }
                if (localModuleInfo == null || moduleInfo.versionCode > localModuleInfo.updateVersionCode) {
                    @Suppress("ktConcatNullable")
                    creditText.text =
                        (if ((localModuleInfo == null) || (moduleInfo.version == localModuleInfo.version)) moduleInfo.version else localModuleInfo.version + " (" + getString(
                            R.string.module_last_update
                        ) + " " + moduleInfo.version + ")") + " " + getString(
                            R.string.module_by
                        ) + " " + moduleInfo.author
                } else {
                    val updateVersionOurs: String?
                    @Suppress("ktConcatNullable")
                    updateVersionOurs =
                        if (localModuleInfo.updateVersion != null) localModuleInfo.updateVersion + " (" + localModuleInfo.updateVersionCode + ")" else localModuleInfo.version + " (" + localModuleInfo.versionCode + ")"
                    creditText.text = updateVersionOurs
                }
                // add an onclick listener to the credit text to show the versionCode
                creditText.setOnClickListener { _: View? ->
                    // if both local and remote moduleInfo are available, show the versionCode of both
                    if (localModuleInfo != null) {
                        // if moduleInfo and localModuleInfo have the same versionCode, only show one, otherwise show both
                        if (localModuleInfo.versionCode == moduleInfo.versionCode) {
                            Toast.makeText(
                                itemView.context,
                                getString(R.string.module_version) + " " + localModuleInfo.version + " (" + localModuleInfo.versionCode + ")",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            // format is Version: version (versionCode) | Remote Version: version (versionCode)
                            Toast.makeText(
                                itemView.context,
                                getString(R.string.module_version) + " " + localModuleInfo.version + " (" + localModuleInfo.versionCode + ") | " + getString(
                                    R.string.module_remote_version
                                ) + " " + moduleInfo.version + " (" + moduleInfo.versionCode + ")",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        Toast.makeText(
                            itemView.context,
                            getString(R.string.module_remote_version) + " " + moduleInfo.version + " (" + moduleInfo.versionCode + ")",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                if (moduleInfo.description == null || moduleInfo.description!!.isEmpty()) {
                    descriptionText.setText(R.string.no_desc_found)
                } else {
                    descriptionText.text = moduleInfo.description
                }
                val updateText = moduleHolder.updateTimeText
                var hasUpdateText = true
                if (updateText.isNotEmpty()) {
                    val repoModule = moduleHolder.repoModule
                    this.updateText.visibility = View.VISIBLE
                    this.updateText.text = """${getString(R.string.module_last_update)} $updateText
${getString(R.string.module_repo)} ${moduleHolder.repoName}""" + if ((repoModule?.qualityText
                            ?: 0) == 0
                    ) "" else "\n" + getString(
                        repoModule!!.qualityText
                    ) + " " + repoModule.qualityValue
                } else if (moduleHolder.moduleId == "hosts") {
                    this.updateText.visibility = View.VISIBLE
                    this.updateText.setText(R.string.magisk_builtin_module)
                } else if (moduleHolder.moduleId.contains("substratum")) {
                    this.updateText.visibility = View.VISIBLE
                    this.updateText.setText(R.string.substratum_builtin_module)
                } else {
                    this.updateText.visibility = View.GONE
                    hasUpdateText = false
                }
                actionButtonsTypes.clear()
                moduleHolder.getButtons(itemView.context, actionButtonsTypes, showCaseMode)
                switchMaterial.isEnabled =
                    !showCaseMode && !moduleHolder.hasFlag(ModuleInfo.FLAG_MODULE_UPDATING)
                for (i in actionsButtons.indices) {
                    val imageButton = actionsButtons[i]
                    if (i < actionButtonsTypes.size) {
                        imageButton!!.visibility = View.VISIBLE
                        imageButton.importantForAccessibility =
                            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                        val button = actionButtonsTypes[i]
                        button!!.update(imageButton, moduleHolder)
                        imageButton.contentDescription = button.name
                    } else {
                        imageButton!!.visibility = View.GONE
                        imageButton.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        imageButton.contentDescription = null
                    }
                }
                if (actionButtonsTypes.isEmpty()) {
                    moduleOptionsHolder.visibility = View.GONE
                    moduleLayoutHelper.visibility = View.GONE
                } else if (actionButtonsTypes.size > 2 || !hasUpdateText) {
                    moduleLayoutHelper.minHeight = FoxDisplay.dpToPixel(36f)
                        .coerceAtLeast(moduleOptionsHolder.height - FoxDisplay.dpToPixel(14f))
                } else {
                    moduleLayoutHelper.minHeight = FoxDisplay.dpToPixel(4f)
                }
                cardView.isClickable = false
                if (moduleHolder.isModuleHolder && moduleHolder.hasFlag(ModuleInfo.FLAG_MODULE_ACTIVE)) {
                    titleText.typeface = Typeface.DEFAULT_BOLD
                } else {
                    titleText.typeface = Typeface.DEFAULT
                }
            } else {
                if (type === ModuleHolder.Type.SEPARATOR && moduleHolder.filterLevel != 0) {
                    buttonAction.visibility = View.VISIBLE
                    buttonAction.setImageResource(moduleHolder.filterLevel)
                    buttonAction.setBackgroundResource(R.drawable.bg_baseline_circle_24)
                } else {
                    buttonAction.visibility =
                        if (type === ModuleHolder.Type.NOTIFICATION) View.VISIBLE else View.GONE
                    buttonAction.background = null
                }
                switchMaterial.visibility = View.GONE
                creditText.visibility = View.GONE
                moduleOptionsHolder.visibility = View.GONE
                moduleLayoutHelper.visibility = View.GONE
                descriptionText.visibility = View.GONE
                updateText.visibility = View.GONE
                titleText.text = " "
                creditText.text = " "
                descriptionText.text = " "
                switchMaterial.isEnabled = false
                actionButtonsTypes.clear()
                for (button in actionsButtons) {
                    button!!.visibility = View.GONE
                    button.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    button.contentDescription = null
                }
                if (type === ModuleHolder.Type.NOTIFICATION) {
                    val notificationType = moduleHolder.notificationType
                    titleText.setText(notificationType?.textId ?: 0)
                    // set title text appearance
                    titleText.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                    if (notificationType != null) {
                        buttonAction.setImageResource(notificationType.iconId)
                    }
                    if (notificationType != null) {
                        cardView.isClickable =
                            notificationType.onClickListener != null || moduleHolder.onClickListener != null
                    }
                    if (notificationType != null) {
                        titleText.typeface =
                            if (notificationType.special) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    }
                } else {
                    cardView.isClickable = moduleHolder.onClickListener != null
                    titleText.typeface = Typeface.DEFAULT
                    titleText.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                }
            }
            if (type === ModuleHolder.Type.SEPARATOR) {
                titleText.setText(if (moduleHolder.separator != null) moduleHolder.separator.title else 0)
            }
            if (DEBUG) {
                if (vType != null) {
                    titleText.text =
                        titleText.text.toString() + " " + formatType(type) + " " + formatType(vType)
                }
            }
            // Coloration system
            val drawable = cardView.background
            if (drawable != null) background = drawable
            invalidPropsChip.visibility = View.GONE
            if (type.hasBackground) {
                if (drawable == null) {
                    cardView.background = background
                }
                var backgroundAttr = androidx.appcompat.R.attr.colorBackgroundFloating
                var foregroundAttr = com.google.android.material.R.attr.colorOnBackground
                if (type === ModuleHolder.Type.NOTIFICATION) {
                    if (moduleHolder.notificationType != null) {
                        foregroundAttr = moduleHolder.notificationType.foregroundAttr
                    }
                    if (moduleHolder.notificationType != null) {
                        backgroundAttr = moduleHolder.notificationType.backgroundAttr
                    }
                } else if (type === ModuleHolder.Type.INSTALLED && moduleHolder.hasFlag(ModuleInfo.FLAG_METADATA_INVALID)) {
                    invalidPropsChip.setOnClickListener { view: View ->
                        val builder = MaterialAlertDialogBuilder(view.context)
                        builder.setTitle(R.string.low_quality_module)
                            .setMessage(R.string.low_quality_module_desc).setCancelable(true)
                            .setPositiveButton(
                                R.string.ok
                            ) { x: DialogInterface, _: Int -> x.dismiss() }
                            .show()
                    }
                    // Backup restore
                    // foregroundAttr = R.attr.colorOnError;
                    // backgroundAttr = R.attr.colorError;
                }
                val theme = cardView.context.theme
                val value = TypedValue()
                theme.resolveAttribute(backgroundAttr, value, true)
                @ColorInt var bgColor = value.data
                theme.resolveAttribute(foregroundAttr, value, true)
                @ColorInt val fgColor = value.data
                // Fix card background being invisible on light theme
                if (bgColor == Color.WHITE) {
                    bgColor = -0x70708
                }
                // if force_transparency is true or theme is transparent_light, set diff bgColor
                // get string value of Theme
                val themeName = theme.toString()
                if (theme.resources.getBoolean(R.bool.force_transparency) || themeName.contains("transparent")) {
                    if (BuildConfig.DEBUG) Timber.d("Theme is transparent, fixing bgColor")
                    bgColor = ColorUtils.setAlphaComponent(bgColor, 0x70)
                }
                titleText.setTextColor(fgColor)
                buttonAction.setColorFilter(fgColor)
                cardView.setCardBackgroundColor(bgColor)
            } else {
                val theme = titleText.context.theme
                val value = TypedValue()
                theme.resolveAttribute(
                    com.google.android.material.R.attr.colorOnBackground,
                    value,
                    true
                )
                buttonAction.setColorFilter(value.data)
                titleText.setTextColor(value.data)
                cardView.background = null
            }
            if (type === ModuleHolder.Type.FOOTER) {
                titleText.minHeight = moduleHolder.footerPx
            } else {
                titleText.minHeight = 0
            }
            this.moduleHolder = moduleHolder
            initState = false
            return false
        }
    }

    companion object {
        private const val DEBUG = false
        private fun formatType(type: ModuleHolder.Type): String {
            return type.name.substring(0, 3) + "_" + type.ordinal
        }
    }
}