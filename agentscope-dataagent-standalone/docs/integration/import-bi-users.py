"""Read only selected XLSX columns and import through the authenticated admin API."""
import argparse
import getpass
import json
from pathlib import Path
import urllib.request
import zipfile
import xml.etree.ElementTree as ET

NS = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}


def read_users(path):
    with zipfile.ZipFile(path) as book:
        if sum(i.file_size for i in book.infolist()) > 50_000_000:
            raise ValueError("Workbook exceeds the import size limit")
        strings = []
        if "xl/sharedStrings.xml" in book.namelist():
            root = ET.fromstring(book.read("xl/sharedStrings.xml"))
            strings = ["".join(n.itertext()) for n in root.findall("s:si", NS)]
        sheet = ET.fromstring(book.read("xl/worksheets/sheet1.xml"))
        rows = []
        for row in sheet.findall("s:sheetData/s:row", NS):
            values = {}
            for cell in row.findall("s:c", NS):
                column = "".join(c for c in cell.attrib["r"] if c.isalpha())
                if cell.find("s:f", NS) is not None:
                    raise ValueError("Formula cells are not accepted in user imports")
                value = cell.findtext("s:v", "", NS)
                if cell.attrib.get("t") == "s":
                    value = strings[int(value)]
                elif cell.attrib.get("t") == "inlineStr":
                    value = "".join(cell.find("s:is", NS).itertext())
                values[column] = value.strip()
            if values:
                rows.append(values)
        if not rows:
            raise ValueError("Empty workbook")
        header = {v: k for k, v in rows[0].items()}
        mapping = {"loginname": "username", "name": "displayName", "company": "company", "dept": "department"}
        if not mapping.keys() <= header.keys():
            raise ValueError("Required columns: loginname, name, company, dept")
        return [{target: row.get(header[source], "") for source, target in mapping.items()}
                for row in rows[1:] if any(row.get(header[k], "") for k in mapping)]


def main():
    parser = argparse.ArgumentParser(description="BI user directory: preview by default; never imports BI passwords")
    parser.add_argument("xlsx", type=Path)
    parser.add_argument("--url", help="Application URL including /dify/dataagent/ prefix")
    parser.add_argument("--apply", action="store_true", help="Write changes; default is server-side dry run")
    parser.add_argument("--check-only", action="store_true", help="Inspect local structure without network access")
    args = parser.parse_args()
    users = read_users(args.xlsx)
    if args.check_only:
        accounts = [u["username"].casefold() for u in users]
        print(json.dumps({"rows": len(users), "duplicate_accounts": len(accounts) - len(set(accounts)),
                          "empty_accounts": sum(not a for a in accounts)}, ensure_ascii=False))
        return
    if not args.url:
        parser.error("--url is required unless --check-only is selected")
    token = getpass.getpass("Platform administrator JWT (hidden): ")
    payload = json.dumps({"users": users, "dryRun": not args.apply}).encode()
    request = urllib.request.Request(args.url.rstrip("/") + "/api/admin/user-directory/import", payload,
                                    {"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=120) as response:
        print(response.read().decode())


if __name__ == "__main__":
    main()
