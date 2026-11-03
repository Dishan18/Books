"""
Builds the Excel and PowerPoint files the office tests read. They are made with openpyxl and
python-pptx, independent writers of the real formats (python-pptx starts from PowerPoint's own
default template, with its theme, master and layouts), so the readers are tested against genuine
files rather than hand-written XML.

    pip install openpyxl python-pptx pillow
    python make_fixtures.py
"""
import datetime
import io
import os

from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill
from PIL import Image
from pptx import Presentation
from pptx.chart.data import CategoryChartData
from pptx.dml.color import RGBColor
from pptx.enum.chart import XL_CHART_TYPE
from pptx.enum.shapes import MSO_CONNECTOR, MSO_SHAPE
from pptx.util import Emu, Inches, Pt

HERE = os.path.dirname(os.path.abspath(__file__))


def workbook():
    wb = Workbook()
    ws = wb.active
    ws.title = "Budget"
    ws["A1"] = "Item"
    ws["A1"].font = Font(bold=True)
    ws["A1"].fill = PatternFill("solid", fgColor="FFFF00")
    ws["B1"] = "Amount"
    ws["C1"] = "Date"
    ws["D1"] = "Share"
    ws["A2"] = "Rent"
    ws["B2"] = 1234.5
    ws["B2"].number_format = "#,##0.00"
    ws["C2"] = datetime.date(2024, 3, 15)
    ws["C2"].number_format = "d-mmm-yy"
    ws["D2"] = 0.256
    ws["D2"].number_format = "0.0%"
    ws["A3"] = "Food"
    ws["B3"] = -42
    ws["B3"].number_format = '"$"#,##0.00_);("$"#,##0.00)'
    ws["B3"].font = Font(color="FF0000")
    ws["C3"] = datetime.datetime(2024, 3, 15, 13, 45, 30)
    ws["C3"].number_format = "yyyy-mm-dd hh:mm:ss"
    ws["D3"] = True
    ws["A4"] = "Total"
    ws["B4"] = "=SUM(B2:B3)"
    ws["A5"] = "A note long enough to spill into the empty cells beside it"
    ws["A6"] = "Wrapped text in a narrow cell"
    ws["A6"].alignment = Alignment(wrap_text=True, horizontal="center", vertical="top")
    ws["E7"] = 12345678901234
    ws["E8"] = 0.1 + 0.2
    ws.merge_cells("A10:C10")
    ws["A10"] = "Merged"
    ws.column_dimensions["A"].width = 20
    ws.row_dimensions[9].hidden = True
    ws.freeze_panes = "B2"

    hidden = wb.create_sheet("Secret")
    hidden["A1"] = "not shown"
    hidden.sheet_state = "hidden"

    second = wb.create_sheet("Second")
    second["B2"] = "Hello"
    second.sheet_view.showGridLines = False
    wb.save(os.path.join(HERE, "sheet.xlsx"))


EXCEL_PARTS = {
    "[Content_Types].xml": """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/><Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>""",
    "_rels/.rels": """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>""",
    "docProps/core.xml": """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Quarterly figures</dc:title><dc:creator>Test Author</dc:creator></cp:coreProperties>""",
    "xl/workbook.xml": """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><workbookPr date1904="1"/><sheets><sheet name="Figures" sheetId="1" r:id="rId1"/></sheets></workbook>""",
    "xl/_rels/workbook.xml.rels": """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="theme/theme1.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/><Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/></Relationships>""",
    "xl/theme/theme1.xml": """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Office"><a:themeElements><a:clrScheme name="Office"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="44546A"/></a:dk2><a:lt2><a:srgbClr val="E7E6E6"/></a:lt2><a:accent1><a:srgbClr val="4472C4"/></a:accent1><a:accent2><a:srgbClr val="ED7D31"/></a:accent2><a:accent3><a:srgbClr val="A5A5A5"/></a:accent3><a:accent4><a:srgbClr val="FFC000"/></a:accent4><a:accent5><a:srgbClr val="5B9BD5"/></a:accent5><a:accent6><a:srgbClr val="70AD47"/></a:accent6><a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme><a:fontScheme name="Office"><a:majorFont><a:latin typeface="Calibri Light"/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/></a:minorFont></a:fontScheme></a:themeElements></a:theme>""",
    "xl/styles.xml": """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="164" formatCode="&quot;€&quot;#,##0.00"/></numFmts><fonts count="2"><font><sz val="11"/><color theme="1"/><name val="Calibri"/></font><font><b/><i/><sz val="14"/><color theme="4" tint="-0.249977111117893"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor indexed="13"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="4"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="14" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"><alignment horizontal="center"/></xf></cellXfs></styleSheet>""",
    "xl/sharedStrings.xml": """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="3" uniqueCount="3"><si><t>Region</t></si><si><r><rPr><b/></rPr><t>Rich </t></r><r><t xml:space="preserve">text</t></r><rPh sb="0" eb="1"><t>ignored</t></rPh></si><si><t>Line_x000D_break</t></si></sst>""",
    "xl/worksheets/sheet1.xml": """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="s" s="3"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c></row><row r="2"><c r="A2" s="1"><v>1234.5</v></c><c r="B2" s="2"><v>0</v></c><c r="C2" t="e"><v>#DIV/0!</v></c><c r="D2" t="str"><f>A1&amp;""</f><v>Region</v></c></row></sheetData></worksheet>""",
}


def excel_written():
    """The parts Excel itself writes and openpyxl doesn't: shared strings, theme colours, a 1904 date system."""
    import zipfile
    with zipfile.ZipFile(os.path.join(HERE, "shared.xlsx"), "w", zipfile.ZIP_DEFLATED) as z:
        for name, text in EXCEL_PARTS.items():
            z.writestr(name, text)


def png(color):
    buffer = io.BytesIO()
    Image.new("RGB", (40, 30), color).save(buffer, "PNG")
    buffer.seek(0)
    return buffer


def deck():
    prs = Presentation()  # PowerPoint's default 4:3 template

    title = prs.slides.add_slide(prs.slide_layouts[0])
    title.shapes.title.text = "Hello Books"
    title.placeholders[1].text = "A test deck"

    bullets = prs.slides.add_slide(prs.slide_layouts[1])
    bullets.shapes.title.text = "Agenda"
    body = bullets.placeholders[1].text_frame
    body.text = "First point"
    p = body.add_paragraph()
    p.text = "A detail"
    p.level = 1
    p = body.add_paragraph()
    p.text = "Second point"

    blank = prs.slides.add_slide(prs.slide_layouts[6])
    box = blank.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0.5), Inches(0.5), Inches(3), Inches(1))
    box.fill.solid()
    box.fill.fore_color.rgb = RGBColor(0xFF, 0x00, 0x00)
    box.text_frame.text = "Red box"
    text = blank.shapes.add_textbox(Inches(4), Inches(0.5), Inches(4), Inches(1))
    text.text_frame.text = "Plain text"
    run = text.text_frame.paragraphs[0].runs[0]
    run.font.size = Pt(24)
    run.font.bold = True
    run.font.color.rgb = RGBColor(0x12, 0x34, 0x56)
    blank.shapes.add_picture(png((0, 128, 255)), Inches(0.5), Inches(2), Inches(2), Inches(1.5))
    group = blank.shapes.add_group_shape()
    group.shapes.add_shape(MSO_SHAPE.OVAL, Inches(3), Inches(2), Inches(1), Inches(1))
    group.shapes.add_shape(MSO_SHAPE.RIGHT_ARROW, Inches(4.2), Inches(2), Inches(1), Inches(1))
    table = blank.shapes.add_table(2, 2, Inches(0.5), Inches(4), Inches(4), Inches(1)).table
    table.cell(0, 0).text = "Head"
    table.cell(1, 1).text = "Cell"
    data = CategoryChartData()
    data.categories = ["A", "B"]
    data.add_series("S", (1, 2))
    blank.shapes.add_chart(XL_CHART_TYPE.COLUMN_CLUSTERED, Inches(5), Inches(4), Inches(4), Inches(3), data)
    line = blank.shapes.add_connector(MSO_CONNECTOR.STRAIGHT, Inches(5), Inches(2), Inches(8), Inches(3))
    line.line.width = Emu(25400)
    prs.save(os.path.join(HERE, "deck.pptx"))


if __name__ == "__main__":
    workbook()
    excel_written()
    deck()
