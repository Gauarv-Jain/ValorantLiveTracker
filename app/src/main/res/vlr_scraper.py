import requests
import re
import time
import sys

DEFAULT_MATCH_URL = "https://www.vlr.gg/626540/bbl-esports-vs-nrg-valorant-masters-santiago-2026-ubqf"
MATCH_URL = sys.argv[1] if len(sys.argv) > 1 else None
NTFY_URL = "https://ntfy.sh/vlr_live_v1"
POLL_INTERVAL = 5
MATCH_POINT = 12

TEAM_A = "BBL"
TEAM_B = "NRG"
TEAM_A_LOGO = "auto"
TEAM_B_LOGO = "auto"

TOURNAMENT_NAME = "Valorant Masters Santiago 2026"
TOURNAMENT_EMOJI = "🏆"
TOURNAMENT_STAGE = "Upper Bracket QF"
NOTIFICATION_FORMAT = "compact"

last_score = None

def find_live_ct_tier1_match():
    try:
        response = requests.get("https://www.vlr.gg/matches", timeout=10)
        response.raise_for_status()
        html = response.text
        
        match_blocks = re.findall(r"<a[^>]*href=\"(/[0-9]+/[^\"]+?)\"[^>]*class=\"[^\"]*match[^\"]*\"[^>]*>.*?</a>", html, re.DOTALL)
        
        for match_link in match_blocks:
            try:
                match_url = "https://www.vlr.gg" + match_link
                match_response = requests.get(match_url, timeout=10)
                match_html = match_response.text
                
                is_ct = "Champions Tour" in match_html or "champions tour" in match_html.lower()
                is_tier1 = "Tier 1" in match_html or "tier 1" in match_html.lower()
                is_live = "LIVE" in match_html or "live" in match_html.lower() or match_response.status_code == 200
                
                if is_ct and is_tier1 and is_live:
                    return match_url
            except:
                continue
        
        print("[!] No live Champions Tour Tier 1 match found right now")
        return None
        
    except Exception as e:
        print("[!] Error searching for match: " + str(e))
        return None

def send_notification(msg, is_mp=False):
    headers = {
        "X-ID": "vlr_live_v1",
        "X-Tag": "vlr_score",
        "X-Ongoing": "yes",
        "Content-Type": "text/plain"
    }
    if is_mp:
        headers["X-Priority"] = "5"
        headers["X-Vibration"] = "1000,500,1000"
    
    try:
        response = requests.post(NTFY_URL, data=msg.encode("utf-8"), headers=headers, timeout=5)
        if response.status_code == 200:
            print("[+] Notification sent")
        else:
            print("[!] Failed: " + str(response.status_code))
    except Exception as e:
        print("[!] Error: " + str(e))

def get_team_logos(html):
    patterns_a = [
        r"<img[^>]*src=\"([^\"]+)\"[^>]*alt=\"BBL[^\"]*team logo\"",
        r"<img[^>]*alt=\"BBL[^\"]*team logo\"[^>]*src=\"([^\"]+)\""
    ]
    patterns_b = [
        r"<img[^>]*src=\"([^\"]+)\"[^>]*alt=\"NRG[^\"]*team logo\"",
        r"<img[^>]*alt=\"NRG[^\"]*team logo\"[^>]*src=\"([^\"]+)\""
    ]
    
    logo_a = None
    logo_b = None
    
    for p in patterns_a:
        m = re.search(p, html)
        if m:
            logo_a = m.group(1)
            break
    
    for p in patterns_b:
        m = re.search(p, html)
        if m:
            logo_b = m.group(1)
            break
    
    if logo_a and logo_a.startswith("//"):
        logo_a = "https:" + logo_a
    if logo_b and logo_b.startswith("//"):
        logo_b = "https:" + logo_b
    
    return (logo_a, logo_b)

def get_all_maps(html):
    header_pattern = r"<div class=\"vm-stats-game-header\">(.*?)</div>\s*<div style=\"text-align: center"
    headers = re.findall(header_pattern, html, re.DOTALL)
    
    if not headers:
        return None
    
    maps = []
    score_pattern = r"<div[^>]*class=\"[^\"]*score[^\"]*\"[^>]*?>(\d+)"
    map_name_pattern = r"(?:Pearl|Abyss|Haven|Bind|Breeze|Icebox|Corrode|Split)"
    
    for idx, header_html in enumerate(headers, 1):
        scores = re.findall(score_pattern, header_html)
        if len(scores) < 2:
            continue
        
        map_match = re.search(map_name_pattern, header_html)
        map_name = map_match.group(0) if map_match else "Unknown"
        
        maps.append({
            "map": map_name,
            "team_a": int(scores[0]),
            "team_b": int(scores[1]),
            "map_num": idx
        })
    
    return maps if maps else None

def get_current_map(html):
    maps = get_all_maps(html)
    return maps[-1] if maps else None

def get_series_score(html):
    maps = get_all_maps(html)
    if not maps:
        return (0, 0)
    
    team_a_wins = 0
    team_b_wins = 0
    
    for m in maps:
        if m["team_a"] >= 13 or m["team_b"] >= 13:
            if m["team_a"] > m["team_b"]:
                team_a_wins += 1
            else:
                team_b_wins += 1
    
    return (team_a_wins, team_b_wins)

def format_message(map_data, all_maps=None, series_score=None):
    if not map_data:
        return "Unable to fetch scores"
    
    if NOTIFICATION_FORMAT == "minimal":
        return format_minimal(map_data, series_score)
    elif NOTIFICATION_FORMAT == "compact":
        return format_compact(map_data, all_maps, series_score)
    else:
        return format_detailed(map_data, all_maps, series_score)

def format_detailed(map_data, all_maps=None, series_score=None):
    msg = [
        "=" * 55,
        TOURNAMENT_EMOJI + " " + TOURNAMENT_NAME,
        "   " + TOURNAMENT_STAGE,
        "=" * 55,
    ]
    
    team_a_disp = TEAM_A_LOGO if not TEAM_A_LOGO.startswith("http") else "![" + TEAM_A + "](" + TEAM_A_LOGO + ")"
    team_b_disp = TEAM_B_LOGO if not TEAM_B_LOGO.startswith("http") else "![" + TEAM_B + "](" + TEAM_B_LOGO + ")"
    
    msg.append("")
    msg.append(team_a_disp + " " + TEAM_A + " vs " + TEAM_B + " " + team_b_disp)
    msg.append("-" * 55)
    
    if all_maps and len(all_maps) > 1:
        msg.append("")
        msg.append("MAP RESULTS:")
        for m in all_maps[:-1]:
            if m["team_a"] >= 13:
                winner = TEAM_A + " wins"
            elif m["team_b"] >= 13:
                winner = TEAM_B + " wins"
            else:
                winner = "In progress"
            msg.append("  Map " + str(m["map_num"]) + " (" + m["map"] + "): " + TEAM_A + " " + str(m["team_a"]) + " - " + str(m["team_b"]) + " " + TEAM_B + " " + winner)
    
    msg.append("")
    msg.append("CURRENT MAP " + str(map_data["map_num"]) + ":")
    msg.append("  " + map_data["map"] + ": " + TEAM_A + " " + str(map_data["team_a"]) + " - " + str(map_data["team_b"]) + " " + TEAM_B)
    
    if series_score:
        msg.append("")
        msg.append("SERIES SCORE:")
        msg.append("  " + TEAM_A + " " + str(series_score[0]) + " - " + str(series_score[1]) + " " + TEAM_B)
    
    msg.append("=" * 55)
    return "\n".join(msg)

def format_compact(map_data, all_maps=None, series_score=None):
    team_a_disp = TEAM_A_LOGO if not TEAM_A_LOGO.startswith("http") else "![" + TEAM_A + "](" + TEAM_A_LOGO + ")"
    team_b_disp = TEAM_B_LOGO if not TEAM_B_LOGO.startswith("http") else "![" + TEAM_B + "](" + TEAM_B_LOGO + ")"
    
    msg = team_a_disp + " " + TEAM_A + " vs " + TEAM_B + " " + team_b_disp + " | " + TOURNAMENT_NAME + "\n"
    msg += "Map " + str(map_data["map_num"]) + " (" + map_data["map"] + "): " + TEAM_A + " " + str(map_data["team_a"]) + " - " + str(map_data["team_b"]) + " " + TEAM_B
    
    if series_score:
        msg += " | Series " + str(series_score[0]) + "-" + str(series_score[1])
    
    if all_maps and len(all_maps) > 1:
        prev = []
        for m in all_maps[:-1]:
            w = TEAM_A if m["team_a"] > m["team_b"] else TEAM_B
            prev.append(m["map"] + " (" + w + ")")
        msg += "\nPrevious: " + ", ".join(prev)
    
    return msg

def format_minimal(map_data, series_score=None):
    msg = "Map " + str(map_data["map_num"]) + " (" + map_data["map"] + "): " + TEAM_A + " " + str(map_data["team_a"]) + " - " + str(map_data["team_b"]) + " " + TEAM_B
    if series_score:
        msg += " | Series " + str(series_score[0]) + "-" + str(series_score[1])
    return msg

def format_scorecard(map_data, all_maps=None, series_score=None):
    msg = TEAM_A + " " + str(map_data["team_a"]) + " - " + str(map_data["team_b"]) + " " + TEAM_B + "\n"
    msg += map_data["map"] + " (Map " + str(map_data["map_num"]) + ")"
    if series_score:
        msg += " | Series " + str(series_score[0]) + "-" + str(series_score[1])
    return msg

def check_match_point(map_data):
    if not map_data:
        return False, None
    if map_data["team_a"] >= MATCH_POINT:
        return True, TEAM_A
    elif map_data["team_b"] >= MATCH_POINT:
        return True, TEAM_B
    return False, None

def main():
    global last_score, TEAM_A_LOGO, TEAM_B_LOGO, MATCH_URL
    
    if not MATCH_URL or MATCH_URL == DEFAULT_MATCH_URL:
        print("[*] Searching for live Champions Tour Tier 1 match...")
        found_url = find_live_ct_tier1_match()
        if found_url:
            MATCH_URL = found_url
            print("[+] Found match: " + MATCH_URL)
        else:
            print("[!] No live Champions Tour Tier 1 match found. Using default URL.")
            MATCH_URL = DEFAULT_MATCH_URL
    
    print("[*] VLR Live Score Scraper")
    print("[*] Match: " + MATCH_URL)
    print("[*] Poll: " + str(POLL_INTERVAL) + "s")
    print("-" * 50)
    
    send_notification(TEAM_A + " vs " + TEAM_B + " - Monitoring live")
    
    logos_done = False
    
    while True:
        try:
            response = requests.get(MATCH_URL, timeout=10)
            response.raise_for_status()
            html = response.text
            
            if not logos_done:
                if TEAM_A_LOGO == "auto" or TEAM_B_LOGO == "auto":
                    logo_a, logo_b = get_team_logos(html)
                    TEAM_A_LOGO = logo_a if logo_a else "🟦"
                    TEAM_B_LOGO = logo_b if logo_b else "🟩"
                    if logo_a and logo_b:
                        print("[+] Logos extracted")
                    else:
                        print("[!] Using default logos")
                logos_done = True
            
            all_maps = get_all_maps(html)
            map_data = get_current_map(html)
            series_score = get_series_score(html)
            
            if map_data:
                current = (map_data["team_a"], map_data["team_b"], map_data["map"])
                
                if current != last_score:
                    msg = format_message(map_data, all_maps, series_score)
                    print("[+] Score Update:")
                    print(msg)
                    
                    is_mp, winner = check_match_point(map_data)
                    
                    if is_mp:
                        mp_msg = "MATCH POINT! " + winner + " wins Map " + str(map_data["map_num"]) + " (" + map_data["map"] + ") " + str(map_data["team_a"]) + "-" + str(map_data["team_b"])
                        if series_score:
                            mp_msg += " | Series " + str(series_score[0]) + "-" + str(series_score[1])
                        send_notification(mp_msg, is_mp=True)
                    else:
                        simple_msg = format_scorecard(map_data, all_maps, series_score)
                        send_notification(simple_msg)
                    
                    last_score = current
                else:
                    print("[=] No change: Map " + str(map_data["map_num"]) + " (" + map_data["map"] + ") " + str(map_data["team_a"]) + "-" + str(map_data["team_b"]) + " | Series " + str(series_score[0]) + "-" + str(series_score[1]))
            else:
                print("[!] Could not extract scores")
            
            print("[*] Next poll in " + str(POLL_INTERVAL) + "s...")
            time.sleep(POLL_INTERVAL)
            
        except requests.exceptions.RequestException as e:
            print("[!] Network error: " + str(e))
            time.sleep(POLL_INTERVAL)
        except Exception as e:
            print("[!] Error: " + str(e))
            time.sleep(POLL_INTERVAL)

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("")
        print("[*] Stopped by user")