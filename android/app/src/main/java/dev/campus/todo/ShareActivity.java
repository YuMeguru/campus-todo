package dev.campus.todo;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * 前端的手动补录入口：从微信长按消息“分享到本应用”，或在任意输入框选中文字后
 * 用“处理文本”菜单送进来。静音群与历史消息只能靠这条路补。
 */
public final class ShareActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        CharSequence text = getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text == null) {
            text = getIntent().getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        }
        String body = Filter.clean(text == null ? "" : text.toString());
        if (body.isEmpty()) {
            Toast.makeText(this, "没有可补录的文字", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Model.Raw raw = new Model.Raw();
        raw.pkg = "manual";
        raw.pkgLabel = "手动补录";
        raw.title = "";
        raw.body = body;
        raw.postedAt = System.currentTimeMillis();
        raw.ingest = "share";
        raw.state = "new";

        long id = Db.get(this).insertRaw(raw);
        Toast.makeText(this,
                id < 0 ? "这条已经补录过了" : "已补录，回首页点“一键整理待办”",
                Toast.LENGTH_SHORT).show();
        finish();
    }
}
