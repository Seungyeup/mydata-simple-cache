
[DB rows]
version, apiCode, direction, depth, leading, item
v1, BA01, IN, 0, N, item1
v1, BA01, IN, 0, N, item2
v1, BA01, IN, 0, N, item3
v1, BA01, IN, 0, N, item4
v1, BA01, IN, 1, item4, item5
v1, BA01, IN, 1, item4, item6
v1, BA01, IN, 1, item4, item7
v1, BA01, IN, 2, item7, item8
v1, BA01, IN, 2, item7, item9
v1, BA02, IN, 0, N, item11
v1, BA02, IN, 0, N, item21
v1, BA02, IN, 0, N, item31
v1, BA02, IN, 0, N, item41
v1, BA02, IN, 1, item41, item51
v1, BA02, IN, 1, item41, item61
v1, BA02, IN, 1, item41, item71
v1, BA02, IN, 2, item71, item81
v1, BA02, IN, 2, item71, item91
v1, BA03, IN, 0, N, item13
v1, BA03, IN, 0, N, item23
v1, BA03, IN, 0, N, item33
v1, BA03, IN, 0, N, item43
v1, BA04, IN, 0, N, item14
v1, BA04, IN, 0, N, item24
v1, BA04, IN, 0, N, item34
v1, BA04, IN, 0, N, item44
v1, BA04, IN, 1, item44, item54
v1, BA04, IN, 1, item44, item64

[input json]
{
    "item1" : item1-value,
    "item2" : item2-value,
    "item3" : item3-value,
    "item4" : [
        {
            "item5" : item5-value,
            "item6" : item5-value,
            "item7" : [
                {
                    "item8" : item8-value-1,
                    "item9" : item9-value-1
                },
                {
                    "item8" : item8-value-2,
                    "item9" : item9-value-2
                }
            ]
        },
        {
            "item5" : item5-value,
            "item6" : item5-value,
            "item7" : [
                {
                    "item8" : item8-value-3,
                    "item9" : item9-value-3
                },
                {
                    "item8" : item8-value-4,
                    "item9" : item9-value-5
                }
            ]
        },
    ]
}