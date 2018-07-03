#version 120

uniform float time;
uniform vec4 uvBasalt;
uniform vec4 uvMap;
uniform sampler2D texture;
uniform sampler2D lightMap;
varying vec4 light;


//  Patricio Gonzalez, The Book of Shaders
// https://thebookofshaders.com/
vec2 random2(vec2 st)
{
    st = vec2( dot(st,vec2(127.1,311.7)),
              dot(st,vec2(269.5,183.3)) );
    return -1.0 + 2.0*fract(sin(st)*43758.5453123);
}

float random (vec2 st)
{
    return fract(sin(dot(st.xy,
                         vec2(12.9898,78.233)))*
        43758.5453123);
}

// Ken Perlin's improved smoothstep
float smootherstep(float edge0, float edge1, float x)
{
  // Scale, and clamp x to 0..1 range
  x = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
  // Evaluate polynomial
  return x * x * x * (x * (x * 6 - 15) + 10);
}

// 2D Noise based on Morgan McGuire @morgan3d
// https://www.shadertoy.com/view/4dS3Wd
float noise (in vec2 st)
{
    vec2 i = floor(st);
    vec2 f = fract(st);

    // Four corners in 2D of a tile
    float a = random(i);
    float b = random(i + vec2(1.0, 0.0));
    float c = random(i + vec2(0.0, 1.0));
    float d = random(i + vec2(1.0, 1.0));

    // Smooth Interpolation

    // Cubic Hermine Curve.  Same as SmoothStep()
    vec2 u = f*f*(3.0-2.0*f);
    // u = smoothstep(0.,1.,f);

    // Mix 4 coorners percentages
    return mix(a, b, u.x) +
            (c - a)* u.y * (1.0 - u.x) +
            (d - b) * u.x * u.y;
}

float noise(vec2 st, float period)
{
    return noise(st * period);
    // return random(st);
}

mat2 makem2(in float theta){float c = cos(theta);float s = sin(theta);return mat2(c,-s,s,c);}

mat2 m2 = mat2( 0.80,  0.60, -0.60,  0.80 );

float grid(vec2 p)
{
	float s = sin(p.x)*cos(p.y);
	return s;
}

float flow(in vec2 p)
{
	float z=2.;
	float rz = 0.;
	float flowTime = time * 0.005;
	vec2 bp = p;
	for (float i= 1.;i < 7.;i++ )
	{
		bp += flowTime*1.5;
		vec2 gr = vec2(grid(p*3.-flowTime*2.),grid(p*3.+4.-flowTime*2.))*0.4;
		gr = normalize(gr)*0.4;
		gr *= makem2((p.x+p.y)*.3+flowTime*10.);
		p += gr*0.5;

		rz+= (sin(noise(p*.01, 256.0)*8.)*0.5+0.5) /z;

		p = mix(bp,p,.5);
		z *= 1.7;
		p *= 2.5;
		p*=m2;
		bp *= 2.5;
		bp*=m2;
	}
	return rz;
}

float tnoise (in vec2 st, float t) {
    vec2 i = floor(st);
    vec2 f = fract(st);

    // Four corners in 2D of a tile
    float a = random(i);
    float b = random(i + vec2(1.0, 0.0));
    float c = random(i + vec2(0.0, 1.0));
    float d = random(i + vec2(1.0, 1.0));

    a =  0.5 + sin((0.5 + a) * t) * 0.5;
    b =  0.5 + sin((0.5 + b) * t) * 0.5;
    c =  0.5 + sin((0.5 + c) * t) * 0.5;
    d =  0.5 + sin((0.5 + d) * t) * 0.5;
    // Smooth Interpolation

    // Cubic Hermine Curve.  Same as SmoothStep()
    vec2 u = f*f*(3.0-2.0*f);
    // u = smoothstep(0.,1.,f);

    // Mix 4 coorners percentages
    return mix(a, b, u.x) +
            (c - a)* u.y * (1.0 - u.x) +
            (d - b) * u.x * u.y;
}

//float rand(vec2 co)
//{
//    return fract(sin(dot(co.xy ,vec2(12.9898,78.233))) * 43758.5453);
//}

// Value Noise by Inigo Quilez - iq/2013
// https://www.shadertoy.com/view/lsf3WH
//float noise(vec2 st)
//{
//    vec2 i = floor(st);
//    vec2 f = fract(st);
//
//    vec2 u = f*f*(3.0-2.0*f);
//
//    return mix( mix( dot( random2(i + vec2(0.0,0.0) ), f - vec2(0.0,0.0) ),
//                     dot( random2(i + vec2(1.0,0.0) ), f - vec2(1.0,0.0) ), u.x),
//                mix( dot( random2(i + vec2(0.0,1.0) ), f - vec2(0.0,1.0) ),
//                     dot( random2(i + vec2(1.0,1.0) ), f - vec2(1.0,1.0) ), u.x), u.y);
//}

// Estimates green color component for black body radiation at input temperature.
// For range we are using, can assume red component is 1.0
float green(float kelvin)
{
    const float a = -155.25485562709179;
    const float b = -0.44596950469579133;
    const float c = 104.49216199393888;
    float x = (kelvin / 100.0) - 2;
    return (a + b * x + c * log(x)) / 255.0;
}

// Estimates blue color component for black body radiation at input temperature.
// For range we are using, can assume red component is 1.0
float blue(float kelvin)
{
	if(kelvin < 2000.0) return 0.0;
    const float a = -254.76935184120902;
    const float b = 0.8274096064007395;
    const float c = 115.67994401066147;
    float x = (kelvin / 100.0) - 10.0;
    return (a + b * x + c * log(x)) / 255.0;
}

void main()
{

	vec2 uvTex = vec2(gl_TexCoord[0]);
	vec4 texColor = texture2D(texture, uvTex);
    vec4 baseColor = vec4(texColor.rgb * gl_Color.rgb * light.rgb, 1.0);

    vec2 uvRel = (uvTex - uvBasalt.st) / uvBasalt.pq;
    vec2 uvAlpa = uvRel * uvMap.pq + uvMap.st;
    vec4 mapColor = texture2D(texture, uvAlpa);


    // map texture channels are as follows
    // r = broad glow
    // g = cracks with glow
    // b = spotty cracks with glow
    // a = perlin noise


    float i = mapColor.b * smootherstep(0.0, 0.25, gl_Color.a);

    float j = mapColor.a * smootherstep(0.15, 0.45, gl_Color.a);
    i = max(i, j);

    j = mapColor.g * smootherstep(0.25, 0.5, gl_Color.a);
    i = max(i, j);

    if(gl_Color.a > 0.5)
    {
    	float a = gl_Color.a * 2.0 - 1.0;
		j =  a * smootherstep(0.65 - 0.65 * a * a, 0.85, mapColor.r);
		i = max(i, j);
    }

    // ax + bxx + cxxx
    // x(a + bx + cxx)
    // x(a +x(b + cx))
    float kelvin = 600 + gl_Color.a * i * i * mapColor.r * (200.0 + mapColor.r * ( 800.0 + mapColor.r * 4000.0));
    //kelvin += smootherstep(0.0, 1.0, i2 * gl_Color.a * gl_Color.a) * mapColor.r * 1400.0;


    float mix = smootherstep(0.0, 0.95, i);

    if(mix > 0.83)
		kelvin *= (0.9 + tnoise(uvRel * 512.0, time * 2.0) * 0.2);

    // shifting the blue curve out a tad - looks better
    vec4 hotColor = vec4(1.0, green(kelvin), blue(kelvin - 1500.0), 1.0);

    gl_FragColor = mix(baseColor, hotColor, mix);
}


