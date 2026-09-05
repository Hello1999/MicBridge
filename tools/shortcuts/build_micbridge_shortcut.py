#!/usr/bin/env python3
"""Generate an unsigned Apple Shortcuts file for the MicBridge Action Button flow.

The generated shortcut sends exactly one `POST /v1/mic/toggle` per run, reuses a
single request id for both the request header and the response check, and drives
the documented 1/2/3 "Vibrate Device" branches. It never stores mic state on the
iPhone and never calls /open, /block or /status.

The output is an UNSIGNED shortcut plist. See docs/IPHONE_SHORTCUT_ZH.md for the
import, signing and verification notes.
"""

import argparse
import plistlib
import uuid

# Object replacement character: the placeholder Shortcuts uses for inline tokens.
OBJ = "￼"


def uid() -> str:
    return str(uuid.uuid4()).upper()


def text(literal: str) -> dict:
    """A text token with no attachments."""
    return {"Value": {"string": literal}, "WFSerializationType": "WFTextTokenString"}


def text_with(parts) -> dict:
    """A text token mixing literal strings with ("var", name) attachments."""
    string = ""
    attachments = {}
    for part in parts:
        if isinstance(part, str):
            string += part
        else:
            attachments["{%d, 1}" % len(string)] = {
                "Type": "Variable",
                "VariableName": part[1],
            }
            string += OBJ
    return {
        "Value": {"string": string, "attachmentsByRange": attachments},
        "WFSerializationType": "WFTextTokenString",
    }


def attach_var(name: str) -> dict:
    return {
        "Value": {"Type": "Variable", "VariableName": name},
        "WFSerializationType": "WFTextTokenAttachment",
    }


def attach_out(output_uuid: str, output_name: str) -> dict:
    return {
        "Value": {
            "Type": "ActionOutput",
            "OutputUUID": output_uuid,
            "OutputName": output_name,
        },
        "WFSerializationType": "WFTextTokenAttachment",
    }


def action(identifier: str, **params) -> dict:
    return {
        "WFWorkflowActionIdentifier": identifier,
        "WFWorkflowActionParameters": params,
    }


def set_variable(name: str, value: dict) -> dict:
    return action("is.workflow.actions.setvariable", WFVariableName=name, WFInput=value)


def number_to_variable(name: str, value: int) -> list:
    """Number action feeding a Set Variable action."""
    number_uuid = uid()
    return [
        action(
            "is.workflow.actions.number",
            UUID=number_uuid,
            WFNumberActionNumber=str(value),
        ),
        set_variable(name, attach_out(number_uuid, "Number")),
    ]


def text_to_variable(name: str, parts) -> list:
    """Text action feeding a Set Variable action."""
    text_uuid = uid()
    return [
        action(
            "is.workflow.actions.gettext",
            UUID=text_uuid,
            WFTextActionText=text_with(parts),
        ),
        set_variable(name, attach_out(text_uuid, "Text")),
    ]


def dictionary_value(source_variable: str, key: str, into_variable: str) -> list:
    """Get Dictionary Value feeding a Set Variable action."""
    get_uuid = uid()
    return [
        action(
            "is.workflow.actions.getvalueforkey",
            UUID=get_uuid,
            WFInput=attach_var(source_variable),
            WFDictionaryKey=text(key),
            WFGetDictionaryValueType="Value",
        ),
        set_variable(into_variable, attach_out(get_uuid, "Dictionary Value")),
    ]


def if_equals(left_variable: str, right_variable: str, body: list) -> list:
    """Flat `If <left> is <right>` block with no else branch."""
    group = uid()
    return [
        action(
            "is.workflow.actions.conditional",
            UUID=uid(),
            GroupingIdentifier=group,
            WFControlFlowMode=0,
            WFInput=attach_var(left_variable),
            WFCondition=4,  # "is"
            WFConditionalActionString=text_with([("var", right_variable)]),
        ),
        *body,
        action(
            "is.workflow.actions.conditional",
            GroupingIdentifier=group,
            WFControlFlowMode=2,
        ),
    ]


def build_actions(url: str, token: str) -> list:
    actions = []

    # 1. One unique request id per run, reused by the header and the response check.
    date_uuid, format_uuid, random_uuid = uid(), uid(), uid()
    actions += [
        action(
            "is.workflow.actions.date",
            UUID=date_uuid,
            WFDateActionMode="Current Date",
        ),
        action(
            "is.workflow.actions.format.date",
            UUID=format_uuid,
            WFDate=attach_out(date_uuid, "Current Date"),
            WFDateFormatStyle="Custom",
            WFDateFormat=text("yyyyMMddHHmmssSSS"),
        ),
        action(
            "is.workflow.actions.number.random",
            UUID=random_uuid,
            WFRandomNumberMinimum="100000000",
            WFRandomNumberMaximum="999999999",
        ),
    ]
    request_text_uuid = uid()
    actions += [
        action(
            "is.workflow.actions.gettext",
            UUID=request_text_uuid,
            WFTextActionText={
                "Value": {
                    "string": OBJ + "-" + OBJ,
                    "attachmentsByRange": {
                        "{0, 1}": {
                            "Type": "ActionOutput",
                            "OutputUUID": format_uuid,
                            "OutputName": "Formatted Date",
                        },
                        "{2, 1}": {
                            "Type": "ActionOutput",
                            "OutputUUID": random_uuid,
                            "OutputName": "Random Number",
                        },
                    },
                },
                "WFSerializationType": "WFTextTokenString",
            },
        ),
        set_variable("RequestId", attach_out(request_text_uuid, "Text")),
    ]

    # 2. The only HTTP control request the iPhone is allowed to make.
    url_uuid, http_uuid = uid(), uid()
    actions += [
        action("is.workflow.actions.url", UUID=url_uuid, WFURLActionURL=url),
        action(
            "is.workflow.actions.downloadurl",
            UUID=http_uuid,
            WFURL=attach_out(url_uuid, "URL"),
            WFHTTPMethod="POST",
            ShowHeaders=True,
            WFHTTPHeaders={
                "Value": {
                    "WFDictionaryFieldValueItems": [
                        {
                            "WFItemType": 0,
                            "WFKey": text("X-MicBridge-Token"),
                            "WFValue": text(token),
                        },
                        {
                            "WFItemType": 0,
                            "WFKey": text("X-Request-Id"),
                            "WFValue": text_with([("var", "RequestId")]),
                        },
                        {
                            "WFItemType": 0,
                            "WFKey": text("Accept"),
                            "WFValue": text("application/json"),
                        },
                    ]
                },
                "WFSerializationType": "WFDictionaryFieldValue",
            },
        ),
        set_variable("Response", attach_out(http_uuid, "Contents of URL")),
    ]

    # 3. Strict response parsing.
    actions += dictionary_value("Response", "ok", "ResponseOk")
    actions += dictionary_value("Response", "verified", "ResponseVerified")
    actions += dictionary_value("Response", "mic_access", "ResponseState")
    actions += dictionary_value("Response", "request_id", "ResponseRequestId")

    # One composite key replaces the nested If chain from the guide. A missing
    # field, an unknown state or a request-id mismatch all produce a key that
    # matches no expected value, so PulseCount stays at the conservative 3.
    actions += text_to_variable(
        "ResultKey",
        [
            ("var", "ResponseRequestId"),
            "|",
            ("var", "ResponseOk"),
            "|",
            ("var", "ResponseVerified"),
            "|",
            ("var", "ResponseState"),
        ],
    )

    # Shortcuts renders JSON booleans as either "true"/"false" or "1"/"0"
    # depending on iOS version, so both renderings are matched explicitly.
    for name, booleans, state in (
        ("ExpectedOpenA", "|true|true|", "open"),
        ("ExpectedOpenB", "|1|1|", "open"),
        ("ExpectedBlockedA", "|true|true|", "blocked"),
        ("ExpectedBlockedB", "|1|1|", "blocked"),
    ):
        actions += text_to_variable(name, [("var", "RequestId"), booleans + state])

    # 4. Exactly one pulse-count branch, then one Vibrate Device per pulse.
    actions += number_to_variable("PulseCount", 3)
    actions += if_equals("ResultKey", "ExpectedBlockedA", number_to_variable("PulseCount", 2))
    actions += if_equals("ResultKey", "ExpectedBlockedB", number_to_variable("PulseCount", 2))
    actions += if_equals("ResultKey", "ExpectedOpenA", number_to_variable("PulseCount", 1))
    actions += if_equals("ResultKey", "ExpectedOpenB", number_to_variable("PulseCount", 1))

    repeat_group = uid()
    actions += [
        action(
            "is.workflow.actions.repeat.count",
            UUID=uid(),
            GroupingIdentifier=repeat_group,
            WFControlFlowMode=0,
            WFRepeatCount=attach_var("PulseCount"),
        ),
        action("is.workflow.actions.vibrate"),
        action(
            "is.workflow.actions.repeat.count",
            GroupingIdentifier=repeat_group,
            WFControlFlowMode=2,
        ),
    ]
    return actions


def build_shortcut(url: str, token: str) -> dict:
    return {
        "WFWorkflowClientVersion": "2302.0.4",
        "WFWorkflowMinimumClientVersion": 900,
        "WFWorkflowMinimumClientVersionString": "900",
        "WFWorkflowHasOutputFallback": False,
        "WFWorkflowHasShortcutInputVariables": False,
        "WFWorkflowIcon": {
            "WFWorkflowIconStartColor": 4274264319,
            "WFWorkflowIconGlyphNumber": 59511,
        },
        "WFWorkflowImportQuestions": [],
        "WFWorkflowTypes": ["NCWidget", "WatchKit"],
        "WFWorkflowInputContentItemClasses": [
            "WFAppStoreAppContentItem",
            "WFArticleContentItem",
            "WFContactContentItem",
            "WFDateContentItem",
            "WFEmailAddressContentItem",
            "WFFolderContentItem",
            "WFGenericFileContentItem",
            "WFImageContentItem",
            "WFiTunesProductContentItem",
            "WFLocationContentItem",
            "WFDCMapsLinkContentItem",
            "WFAVAssetContentItem",
            "WFPDFContentItem",
            "WFPhoneNumberContentItem",
            "WFRichTextContentItem",
            "WFSafariWebPageContentItem",
            "WFStringContentItem",
            "WFURLContentItem",
        ],
        "WFQuickActionSurfaces": [],
        "WFWorkflowActions": build_actions(url, token),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--url",
        default="http://192.168.5.16:8787/v1/mic/toggle",
        help="MicBridge toggle endpoint shown in the Android UI",
    )
    parser.add_argument(
        "--token",
        default="REPLACE_WITH_MICBRIDGE_TOKEN",
        help="MicBridge token; left as a placeholder by default so the file is not a secret",
    )
    parser.add_argument("--out", default="MicBridgeWorker.shortcut")
    parser.add_argument(
        "--binary",
        action="store_true",
        help="emit a binary plist instead of XML",
    )
    args = parser.parse_args()

    fmt = plistlib.FMT_BINARY if args.binary else plistlib.FMT_XML
    with open(args.out, "wb") as handle:
        plistlib.dump(build_shortcut(args.url, args.token), handle, fmt=fmt)
    print("wrote", args.out)


if __name__ == "__main__":
    main()
