package com.example.agora.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ExpandablePostText(
    text: String,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
    minimizedMaxLines: Int = 3
) {
    var isOverflowing by remember { mutableStateOf(false) }

    Column(modifier = modifier.animateContentSize()) {
        Text(
            text = text,
            maxLines = if (isExpanded) Int.MAX_VALUE else minimizedMaxLines,
            overflow = TextOverflow.Ellipsis,
            fontSize = 16.sp,
            onTextLayout = { result ->
                val overflow = result.hasVisualOverflow || result.lineCount > minimizedMaxLines
                if (isOverflowing != overflow) {
                    isOverflowing = overflow
                }
            }
        )

        if (isOverflowing && !isExpanded) {
            Text(
                text = "... View more",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable { onToggleExpand() }
            )
        } else if (isExpanded && isOverflowing) {
            Text(
                text = "See less",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable { onToggleExpand() }
            )
        }
    }
}
