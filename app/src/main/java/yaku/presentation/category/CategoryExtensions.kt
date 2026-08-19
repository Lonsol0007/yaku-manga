package yaku.presentation.category

import android.content.Context
import androidx.compose.runtime.Composable
import yaku.core.common.i18n.stringResource
import yaku.domain.category.model.Category
import yaku.i18n.MR
import yaku.presentation.core.i18n.stringResource

val Category.visualName: String
    @Composable
    get() = when {
        isSystemCategory -> stringResource(MR.strings.label_default)
        else -> name
    }

fun Category.visualName(context: Context): String =
    when {
        isSystemCategory -> context.stringResource(MR.strings.label_default)
        else -> name
    }
