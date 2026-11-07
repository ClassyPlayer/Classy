#!/usr/bin/env python3
"""
patch_boot_dialog.py - replace the stock "Android is upgrading" boot dialog with a
Classy-style boot screen.

What it does
------------
Android draws that dialog from system_server: PhoneWindowManager.showBootMessage()
creates a ProgressDialog (title "Android is upgrading...", indeterminate spinner,
heavy Holo chrome) on a TYPE_BOOT_PROGRESS window. Its code lives in
/system/framework/android.policy.jar, anonymous class PhoneWindowManager$29
(the Runnable) and PhoneWindowManager$29$1 (the ProgressDialog subclass).

This tool rewrites that jar:

  * the dialog gets a null title, so AlertController hides the whole title panel
    (title_template + divider) instead of drawing "Android is upgrading...";
  * after show(), the new helper ClassyBootScreen.apply(dialog) gives the window a
    solid black background and MATCH_PARENT layout, hides the spinner and paints the
    message in white sans-serif-light at 16sp.

Result on the panel: a plain black screen with one small centred line of text that
still updates ("Updating 3 of 41", "Starting up..." - pair with swap_boot_strings.py).

The window type, the dim amount and the "is it drawn yet" handshake that lets the
display turn on are all left untouched, which is what keeps the boot sequence sane.

Usage
-----
    patch_boot_dialog.py [--check] [--jars DIR] [--java JAVA] <android.policy.jar> [out.jar]

--jars points at a directory holding baksmali.jar, smali.jar, dexlib2.jar, util.jar,
guava.jar, failureaccess.jar, antlr.jar and jcommander.jar (see README).
"""

import argparse
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

HELPER_PATH = "com/android/internal/policy/impl/ClassyBootScreen.smali"
RUNNABLE_PATH = "com/android/internal/policy/impl/PhoneWindowManager$29.smali"

HELPER_SMALI = """.class public final Lcom/android/internal/policy/impl/ClassyBootScreen;
.super Ljava/lang/Object;
.source "ClassyBootScreen.java"

# static fields
.field public static sBar:Landroid/widget/ProgressBar;


# direct methods
.method private constructor <init>()V
    .registers 1

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method


# virtual methods
.method public static apply(Landroid/app/Dialog;)V
    .registers 12
    .param p0, "dialog"    # Landroid/app/Dialog;

    :try_start
    invoke-virtual {p0}, Landroid/app/Dialog;->getWindow()Landroid/view/Window;

    move-result-object v0

    if-eqz v0, :ret

    # Window background: opaque black
    new-instance v1, Landroid/graphics/drawable/ColorDrawable;

    const/high16 v2, -0x1000000

    invoke-direct {v1, v2}, Landroid/graphics/drawable/ColorDrawable;-><init>(I)V

    invoke-virtual {v0, v1}, Landroid/view/Window;->setBackgroundDrawable(Landroid/graphics/drawable/Drawable;)V

    # Window size: fill screen
    const/4 v2, -0x1

    invoke-virtual {v0, v2, v2}, Landroid/view/Window;->setLayout(II)V

    # Window gravity: CENTER (0x11)
    const/16 v2, 0x11

    invoke-virtual {v0, v2}, Landroid/view/Window;->setGravity(I)V

    # DecorView background: opaque black
    invoke-virtual {v0}, Landroid/view/Window;->getDecorView()Landroid/view/View;

    move-result-object v6

    if-eqz v6, :decor_done

    const/high16 v2, -0x1000000

    invoke-virtual {v6, v2}, Landroid/view/View;->setBackgroundColor(I)V

    :decor_done
    # Hide stock spinner (android.R.id.progress = 0x102000d)
    const v2, 0x102000d

    invoke-virtual {p0, v2}, Landroid/app/Dialog;->findViewById(I)Landroid/view/View;

    move-result-object v1

    if-eqz v1, :spin_done

    const/16 v2, 0x8

    invoke-virtual {v1, v2}, Landroid/view/View;->setVisibility(I)V

    :spin_done
    # Clear customPanel background (0x1020279 on this ROM)
    const v2, 0x1020279

    invoke-virtual {p0, v2}, Landroid/app/Dialog;->findViewById(I)Landroid/view/View;

    move-result-object v1

    if-eqz v1, :cp_done

    const/4 v2, 0x0

    invoke-virtual {v1, v2}, Landroid/view/View;->setBackgroundDrawable(Landroid/graphics/drawable/Drawable;)V

    const/4 v2, 0x0

    invoke-virtual {v1, v2, v2, v2, v2}, Landroid/view/View;->setPadding(IIII)V

    :cp_done
    # Clear custom background (0x102002b)
    const v2, 0x102002b

    invoke-virtual {p0, v2}, Landroid/app/Dialog;->findViewById(I)Landroid/view/View;

    move-result-object v1

    if-eqz v1, :cust_done

    const/4 v2, 0x0

    invoke-virtual {v1, v2}, Landroid/view/View;->setBackgroundDrawable(Landroid/graphics/drawable/Drawable;)V

    const/4 v2, 0x0

    invoke-virtual {v1, v2, v2, v2, v2}, Landroid/view/View;->setPadding(IIII)V

    :cust_done
    # Center root dialog LinearLayout inside android.R.id.content (0x1020002)
    const v2, 0x1020002

    invoke-virtual {p0, v2}, Landroid/app/Dialog;->findViewById(I)Landroid/view/View;

    move-result-object v1

    if-eqz v1, :root_done

    instance-of v2, v1, Landroid/view/ViewGroup;

    if-eqz v2, :root_done

    check-cast v1, Landroid/view/ViewGroup;

    invoke-virtual {v1}, Landroid/view/ViewGroup;->getChildCount()I

    move-result v2

    if-lez v2, :root_done

    const/4 v2, 0x0

    invoke-virtual {v1, v2}, Landroid/view/ViewGroup;->getChildAt(I)Landroid/view/View;

    move-result-object v1

    if-eqz v1, :root_done

    # Set LinearLayout gravity to CENTER (0x11)
    instance-of v2, v1, Landroid/widget/LinearLayout;

    if-eqz v2, :skip_ll_grav

    move-object v2, v1

    check-cast v2, Landroid/widget/LinearLayout;

    const/16 v3, 0x11

    invoke-virtual {v2, v3}, Landroid/widget/LinearLayout;->setGravity(I)V

    :skip_ll_grav
    # Set FrameLayout.LayoutParams gravity to CENTER (0x11)
    invoke-virtual {v1}, Landroid/view/View;->getLayoutParams()Landroid/view/ViewGroup$LayoutParams;

    move-result-object v2

    instance-of v3, v2, Landroid/widget/FrameLayout$LayoutParams;

    if-eqz v3, :root_done

    check-cast v2, Landroid/widget/FrameLayout$LayoutParams;

    const/16 v3, 0x11

    iput v3, v2, Landroid/widget/FrameLayout$LayoutParams;->gravity:I

    invoke-virtual {v1, v2}, Landroid/view/View;->setLayoutParams(Landroid/view/ViewGroup$LayoutParams;)V

    :root_done
    # Style the message TextView (android.R.id.message = 0x102000b)
    const v2, 0x102000b

    invoke-virtual {p0, v2}, Landroid/app/Dialog;->findViewById(I)Landroid/view/View;

    move-result-object v1

    if-eqz v1, :ret

    check-cast v1, Landroid/widget/TextView;

    # Text color: white
    const/4 v2, -0x1

    invoke-virtual {v1, v2}, Landroid/widget/TextView;->setTextColor(I)V

    # Text size: 22sp (0x41b00000)
    const/4 v2, 0x1

    const/high16 v3, 0x41b00000

    invoke-virtual {v1, v2, v3}, Landroid/widget/TextView;->setTextSize(IF)V

    # Typeface: sans-serif-light
    const-string v2, "sans-serif-light"

    const/4 v3, 0x0

    invoke-static {v2, v3}, Landroid/graphics/Typeface;->create(Ljava/lang/String;I)Landroid/graphics/Typeface;

    move-result-object v2

    invoke-virtual {v1, v2}, Landroid/widget/TextView;->setTypeface(Landroid/graphics/Typeface;)V

    # Text gravity: CENTER (0x11 = 17)
    const/16 v2, 0x11

    invoke-virtual {v1, v2}, Landroid/widget/TextView;->setGravity(I)V

    # Message parent ViewGroup
    invoke-virtual {v1}, Landroid/widget/TextView;->getParent()Landroid/view/ViewParent;

    move-result-object v2

    instance-of v3, v2, Landroid/view/ViewGroup;

    if-eqz v3, :ret

    check-cast v2, Landroid/view/ViewGroup;

    # If parent is LinearLayout, set orientation VERTICAL (1) and gravity CENTER (0x11)
    instance-of v3, v2, Landroid/widget/LinearLayout;

    if-eqz v3, :skip_parent_ll

    move-object v3, v2

    check-cast v3, Landroid/widget/LinearLayout;

    const/4 v4, 0x1

    invoke-virtual {v3, v4}, Landroid/widget/LinearLayout;->setOrientation(I)V

    const/16 v4, 0x11

    invoke-virtual {v3, v4}, Landroid/widget/LinearLayout;->setGravity(I)V

    :skip_parent_ll
    # Set message LayoutParams to WRAP_CONTENT, gravity CENTER_HORIZONTAL (1)
    invoke-virtual {v1}, Landroid/widget/TextView;->getLayoutParams()Landroid/view/ViewGroup$LayoutParams;

    move-result-object v3

    instance-of v4, v3, Landroid/widget/LinearLayout$LayoutParams;

    if-eqz v4, :skip_msg_lp

    move-object v4, v3

    check-cast v4, Landroid/widget/LinearLayout$LayoutParams;

    const/4 v5, -0x2

    iput v5, v4, Landroid/widget/LinearLayout$LayoutParams;->width:I

    iput v5, v4, Landroid/widget/LinearLayout$LayoutParams;->height:I

    const/4 v5, 0x1

    iput v5, v4, Landroid/widget/LinearLayout$LayoutParams;->gravity:I

    invoke-virtual {v1, v4}, Landroid/widget/TextView;->setLayoutParams(Landroid/view/ViewGroup$LayoutParams;)V

    :skip_msg_lp
    # Create horizontal ProgressBar
    invoke-virtual {p0}, Landroid/app/Dialog;->getContext()Landroid/content/Context;

    move-result-object v3

    new-instance v4, Landroid/widget/ProgressBar;

    const/4 v5, 0x0

    const v7, 0x1010078

    invoke-direct {v4, v3, v5, v7}, Landroid/widget/ProgressBar;-><init>(Landroid/content/Context;Landroid/util/AttributeSet;I)V

    const/4 v5, 0x0

    invoke-virtual {v4, v5}, Landroid/widget/ProgressBar;->setIndeterminate(Z)V

    const/16 v5, 0x64

    invoke-virtual {v4, v5}, Landroid/widget/ProgressBar;->setMax(I)V

    const/4 v5, 0x0

    invoke-virtual {v4, v5}, Landroid/widget/ProgressBar;->setProgress(I)V

    # iPod Classic style progress drawable:
    # Track: ColorDrawable(0xFF444444 = -0xbbbbbc)
    new-instance v5, Landroid/graphics/drawable/ColorDrawable;

    const v7, -0xbbbbbc

    invoke-direct {v5, v7}, Landroid/graphics/drawable/ColorDrawable;-><init>(I)V

    # Fill: ColorDrawable(0xFFFFFFFF = -1)
    new-instance v7, Landroid/graphics/drawable/ColorDrawable;

    const/4 v8, -0x1

    invoke-direct {v7, v8}, Landroid/graphics/drawable/ColorDrawable;-><init>(I)V

    # ClipDrawable(fill, Gravity.LEFT = 3, ClipDrawable.HORIZONTAL = 1)
    new-instance v8, Landroid/graphics/drawable/ClipDrawable;

    const/4 v9, 0x3

    const/4 v10, 0x1

    invoke-direct {v8, v7, v9, v10}, Landroid/graphics/drawable/ClipDrawable;-><init>(Landroid/graphics/drawable/Drawable;II)V

    # LayerDrawable(new Drawable[]{track, clip})
    const/4 v7, 0x2

    new-array v7, v7, [Landroid/graphics/drawable/Drawable;

    const/4 v9, 0x0

    aput-object v5, v7, v9

    const/4 v9, 0x1

    aput-object v8, v7, v9

    new-instance v5, Landroid/graphics/drawable/LayerDrawable;

    invoke-direct {v5, v7}, Landroid/graphics/drawable/LayerDrawable;-><init>([Landroid/graphics/drawable/Drawable;)V

    const/4 v7, 0x0

    const v8, 0x1020000

    invoke-virtual {v5, v7, v8}, Landroid/graphics/drawable/LayerDrawable;->setId(II)V

    const/4 v7, 0x1

    const v8, 0x102000d

    invoke-virtual {v5, v7, v8}, Landroid/graphics/drawable/LayerDrawable;->setId(II)V

    invoke-virtual {v4, v5}, Landroid/widget/ProgressBar;->setProgressDrawable(Landroid/graphics/drawable/Drawable;)V

    # LayoutParams for ProgressBar: width 220px, height 8px, topMargin 20px
    instance-of v5, v2, Landroid/widget/RelativeLayout;

    if-eqz v5, :is_ll

    new-instance v5, Landroid/widget/RelativeLayout$LayoutParams;

    const/16 v7, 0xdc

    const/16 v8, 0x8

    invoke-direct {v5, v7, v8}, Landroid/widget/RelativeLayout$LayoutParams;-><init>(II)V

    const/16 v7, 0xe

    invoke-virtual {v5, v7}, Landroid/widget/RelativeLayout$LayoutParams;->addRule(I)V

    const/4 v7, 0x3

    const v8, 0x102000b

    invoke-virtual {v5, v7, v8}, Landroid/widget/RelativeLayout$LayoutParams;->addRule(II)V

    const/16 v7, 0x14

    iput v7, v5, Landroid/widget/RelativeLayout$LayoutParams;->topMargin:I

    goto :do_add

    :is_ll
    new-instance v5, Landroid/widget/LinearLayout$LayoutParams;

    const/16 v7, 0xdc

    const/16 v8, 0x8

    invoke-direct {v5, v7, v8}, Landroid/widget/LinearLayout$LayoutParams;-><init>(II)V

    const/4 v7, 0x1

    iput v7, v5, Landroid/widget/LinearLayout$LayoutParams;->gravity:I

    const/16 v7, 0x14

    iput v7, v5, Landroid/widget/LinearLayout$LayoutParams;->topMargin:I

    :do_add
    invoke-virtual {v2, v4, v5}, Landroid/view/ViewGroup;->addView(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V

    sput-object v4, Lcom/android/internal/policy/impl/ClassyBootScreen;->sBar:Landroid/widget/ProgressBar;

    # Parse initial message if present
    invoke-virtual {v1}, Landroid/widget/TextView;->getText()Ljava/lang/CharSequence;

    move-result-object v3

    if-eqz v3, :ret

    invoke-static {v3}, Lcom/android/internal/policy/impl/ClassyBootScreen;->update(Ljava/lang/CharSequence;)V
    :try_end
    .catch Ljava/lang/Throwable; {:try_start .. :try_end} :catch_all

    return-void

    :catch_all
    :ret
    return-void
.end method


.method public static update(Ljava/lang/CharSequence;)V
    .registers 10
    .param p0, "msg"    # Ljava/lang/CharSequence;

    :try_start
    sget-object v0, Lcom/android/internal/policy/impl/ClassyBootScreen;->sBar:Landroid/widget/ProgressBar;

    if-eqz v0, :ret

    if-eqz p0, :ret

    invoke-interface {p0}, Ljava/lang/CharSequence;->toString()Ljava/lang/String;

    move-result-object v1

    invoke-virtual {v1}, Ljava/lang/String;->length()I

    move-result v2

    const/4 v3, 0x0

    # find_d1
    :find_d1
    if-ge v3, v2, :parse_fallback

    invoke-virtual {v1, v3}, Ljava/lang/String;->charAt(I)C

    move-result v4

    invoke-static {v4}, Ljava/lang/Character;->isDigit(C)Z

    move-result v4

    if-nez v4, :d1_found

    add-int/lit8 v3, v3, 0x1

    goto :find_d1

    :d1_found
    move v4, v3

    # find_n1_end
    :find_n1_end
    if-ge v3, v2, :got_n1

    invoke-virtual {v1, v3}, Ljava/lang/String;->charAt(I)C

    move-result v5

    invoke-static {v5}, Ljava/lang/Character;->isDigit(C)Z

    move-result v5

    if-eqz v5, :got_n1

    add-int/lit8 v3, v3, 0x1

    goto :find_n1_end

    :got_n1
    move v5, v3

    # find_d2
    :find_d2
    if-ge v3, v2, :parse_fallback

    invoke-virtual {v1, v3}, Ljava/lang/String;->charAt(I)C

    move-result v6

    invoke-static {v6}, Ljava/lang/Character;->isDigit(C)Z

    move-result v6

    if-nez v6, :d2_found

    add-int/lit8 v3, v3, 0x1

    goto :find_d2

    :d2_found
    move v6, v3

    # find_n2_end
    :find_n2_end
    if-ge v3, v2, :got_n2

    invoke-virtual {v1, v3}, Ljava/lang/String;->charAt(I)C

    move-result v7

    invoke-static {v7}, Ljava/lang/Character;->isDigit(C)Z

    move-result v7

    if-eqz v7, :got_n2

    add-int/lit8 v3, v3, 0x1

    goto :find_n2_end

    :got_n2
    move v7, v3

    # Parse cur and total
    invoke-virtual {v1, v4, v5}, Ljava/lang/String;->substring(II)Ljava/lang/String;

    move-result-object v4

    invoke-static {v4}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I

    move-result v4

    invoke-virtual {v1, v6, v7}, Ljava/lang/String;->substring(II)Ljava/lang/String;

    move-result-object v5

    invoke-static {v5}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I

    move-result v5

    if-lez v5, :parse_fallback

    invoke-virtual {v0, v5}, Landroid/widget/ProgressBar;->setMax(I)V

    invoke-virtual {v0, v4}, Landroid/widget/ProgressBar;->setProgress(I)V

    const/4 v6, 0x0

    invoke-virtual {v0, v6}, Landroid/widget/ProgressBar;->setVisibility(I)V

    return-void

    :parse_fallback
    invoke-virtual {v0}, Landroid/widget/ProgressBar;->getMax()I

    move-result v1

    if-lez v1, :ret

    invoke-virtual {v0, v1}, Landroid/widget/ProgressBar;->setProgress(I)V
    :try_end
    .catch Ljava/lang/Throwable; {:try_start .. :try_end} :catch_all

    return-void

    :catch_all
    :ret
    return-void
.end method
"""

OLD_TITLE = """    const v2, 0x1040412

    invoke-virtual {v1, v2}, Landroid/app/ProgressDialog;->setTitle(I)V"""

NEW_TITLE = """    const/4 v2, 0x0

    invoke-virtual {v1, v2}, Landroid/app/ProgressDialog;->setTitle(Ljava/lang/CharSequence;)V"""

OLD_SHOW = """    invoke-virtual {v1}, Landroid/app/ProgressDialog;->show()V"""

NEW_SHOW = """    invoke-virtual {v1}, Landroid/app/ProgressDialog;->show()V

    iget-object v1, p0, Lcom/android/internal/policy/impl/PhoneWindowManager$29;->this$0:Lcom/android/internal/policy/impl/PhoneWindowManager;

    iget-object v1, v1, Lcom/android/internal/policy/impl/PhoneWindowManager;->mBootMsgDialog:Landroid/app/ProgressDialog;

    invoke-static {v1}, Lcom/android/internal/policy/impl/ClassyBootScreen;->apply(Landroid/app/Dialog;)V"""

OLD_MSG = """    invoke-virtual {v1, v2}, Landroid/app/ProgressDialog;->setMessage(Ljava/lang/CharSequence;)V"""

NEW_MSG = """    invoke-virtual {v1, v2}, Landroid/app/ProgressDialog;->setMessage(Ljava/lang/CharSequence;)V

    invoke-static {v2}, Lcom/android/internal/policy/impl/ClassyBootScreen;->update(Ljava/lang/CharSequence;)V"""


def tool_classpath(jars):
    names = [
        "baksmali.jar",
        "smali.jar",
        "dexlib2.jar",
        "util.jar",
        "guava.jar",
        "failureaccess.jar",
        "antlr.jar",
        "jcommander.jar",
    ]
    missing = [n for n in names if not os.path.exists(os.path.join(jars, n))]
    if missing:
        raise SystemExit("missing jars in %s: %s" % (jars, ", ".join(missing)))
    return ":".join(os.path.join(jars, n) for n in names)


def run(cmd):
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode != 0:
        sys.stderr.write(p.stdout[-4000:] + p.stderr[-4000:])
        raise SystemExit("failed: %s" % " ".join(cmd[:3]))
    return p


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("jar")
    ap.add_argument("out", nargs="?")
    ap.add_argument("--jars", default=os.environ.get("SMALI_JARS", ""))
    ap.add_argument("--java", default=os.environ.get("JAVA", "java"))
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    if args.check:
        with zipfile.ZipFile(args.jar) as z:
            data = z.read("classes.dex")
        ok = b"ClassyBootScreen" in data
        print("patched" if ok else "stock")
        return 0 if ok else 1

    if not args.jars:
        raise SystemExit("--jars (or SMALI_JARS) must point at the smali toolchain")
    cp = tool_classpath(args.jars)
    out_jar = args.out or os.path.splitext(args.jar)[0] + "-classy.jar"

    with zipfile.ZipFile(args.jar) as z:
        names = z.namelist()
        dex = z.read("classes.dex")
        manifest = z.read("META-INF/MANIFEST.MF") if "META-INF/MANIFEST.MF" in names else None

    work = tempfile.mkdtemp(prefix="classy-boot-")
    try:
        open(os.path.join(work, "classes.dex"), "wb").write(dex)
        smali_dir = os.path.join(work, "smali")
        run([args.java, "-Xmx2g", "-cp", cp, "org.jf.baksmali.Main",
             "d", "-a", "19", "-o", smali_dir, os.path.join(work, "classes.dex")])

        runnable = os.path.join(smali_dir, RUNNABLE_PATH)
        src = open(runnable).read()
        if "ClassyBootScreen" in src:
            raise SystemExit("jar is already patched")
        if src.count(OLD_TITLE) != 1 or src.count(OLD_SHOW) != 1 or src.count(OLD_MSG) != 1:
            raise SystemExit("unexpected showBootMessage layout - is this the same ROM?")
        src = src.replace(OLD_TITLE, NEW_TITLE).replace(OLD_SHOW, NEW_SHOW).replace(OLD_MSG, NEW_MSG)
        open(runnable, "w").write(src)

        helper = os.path.join(smali_dir, HELPER_PATH)
        os.makedirs(os.path.dirname(helper), exist_ok=True)
        open(helper, "w").write(HELPER_SMALI)

        new_dex = os.path.join(work, "classes-patched.dex")
        run([args.java, "-Xmx2g", "-cp", cp, "org.jf.smali.Main",
             "a", "-a", "19", "-o", new_dex, smali_dir])

        with zipfile.ZipFile(out_jar, "w", zipfile.ZIP_DEFLATED) as z:
            if manifest is not None:
                z.writestr("META-INF/MANIFEST.MF", manifest)
            z.writestr("classes.dex", open(new_dex, "rb").read())
        print("wrote %s (%d bytes)" % (out_jar, os.path.getsize(out_jar)))
    finally:
        shutil.rmtree(work, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
